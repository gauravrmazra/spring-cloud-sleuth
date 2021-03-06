/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.zipkin;

import java.nio.charset.Charset;
import java.util.Map;

import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.TimelineAnnotation;
import org.springframework.cloud.sleuth.event.ClientReceivedEvent;
import org.springframework.cloud.sleuth.event.ClientSentEvent;
import org.springframework.cloud.sleuth.event.ServerReceivedEvent;
import org.springframework.cloud.sleuth.event.ServerSentEvent;
import org.springframework.cloud.sleuth.event.SpanAcquiredEvent;
import org.springframework.cloud.sleuth.event.SpanReleasedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import com.github.kristofa.brave.SpanCollector;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.AnnotationType;
import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.zipkinCoreConstants;

import lombok.extern.apachecommons.CommonsLog;

/**
 * @author Spencer Gibb
 */
@CommonsLog
public class ZipkinSpanListener {
	private static final Charset UTF_8 = Charset.forName("UTF-8");
	private static final byte[] UNKNOWN_BYTES = "unknown".getBytes(UTF_8);

	private SpanCollector spanCollector;
	/**
	 * Endpoint is the visible IP address of this service, the port it is listening on and
	 * the service name from discovery.
	 */
	// Visible for testing
	Endpoint localEndpoint;

	public ZipkinSpanListener(SpanCollector spanCollector, Endpoint localEndpoint) {
		this.spanCollector = spanCollector;
		this.localEndpoint = localEndpoint;
	}

	@EventListener
	@Order(0)
	public void start(SpanAcquiredEvent event) {
		// Zipkin Span.timestamp corresponds with Sleuth's Span.begin
		assert event.getSpan().getBegin() != 0;
	}

	@EventListener
	@Order(0)
	public void serverReceived(ServerReceivedEvent event) {
		if (event.getParent() != null && event.getParent().isRemote()) {
			// If an inbound RPC call, it should log a "sr" annotation.
			// If possible, it should log a binary annotation of "ca", indicating the
			// caller's address (ex X-Forwarded-For header)
			event.getParent().addTimelineAnnotation(zipkinCoreConstants.SERVER_RECV);
		}
	}

	@EventListener
	@Order(0)
	public void clientSend(ClientSentEvent event) {
		// For an outbound RPC call, it should log a "cs" annotation.
		// If possible, it should log a binary annotation of "sa", indicating the
		// destination address.
		event.getSpan().addTimelineAnnotation(zipkinCoreConstants.CLIENT_SEND);
	}

	@EventListener
	@Order(0)
	public void clientReceive(ClientReceivedEvent event) {
		event.getSpan().addTimelineAnnotation(zipkinCoreConstants.CLIENT_RECV);
	}

	@EventListener
	@Order(0)
	public void serverSend(ServerSentEvent event) {
		if (event.getParent() != null && event.getParent().isRemote()) {
			event.getParent().addTimelineAnnotation(zipkinCoreConstants.SERVER_SEND);
			this.spanCollector.collect(convert(event.getParent()));
		}
	}

	@EventListener
	@Order(0)
	public void release(SpanReleasedEvent event) {
		// Ending a span in zipkin means adding duration and sending it out
		// Zipkin Span.duration corresponds with Sleuth's Span.begin and end
		assert event.getSpan().getEnd() != 0;
		if (event.getSpan().isExportable()) {
			this.spanCollector.collect(convert(event.getSpan()));
		}
	}

	/**
	 * Converts a given Sleuth span to a Zipkin Span.
	 * <ul>
	 * <li>Set ids, etc
	 * <li>Create timeline annotations based on data from Span object.
	 * <li>Create binary annotations based on data from Span object.
	 * </ul>
	 */
	// Visible for testing
	com.twitter.zipkin.gen.Span convert(Span span) {
		com.twitter.zipkin.gen.Span zipkinSpan = new com.twitter.zipkin.gen.Span();

		// A zipkin span without any annotations cannot be queried, add special "lc" to avoid that.
		if (span.getTimelineAnnotations().isEmpty() && span.getAnnotations().isEmpty()) {
			// TODO: javadocs say this isn't nullable!
			byte[] processId = span.getProcessId() != null
					? span.getProcessId().toLowerCase().getBytes(UTF_8)
					: UNKNOWN_BYTES;
			BinaryAnnotation component = new BinaryAnnotation()
					.setAnnotation_type(AnnotationType.STRING)
					.setKey("lc") // LOCAL_COMPONENT
					.setValue(processId)
					.setHost(this.localEndpoint);
			zipkinSpan.addToBinary_annotations(component);
		} else {
			addZipkinAnnotations(zipkinSpan, span, this.localEndpoint);
			addZipkinBinaryAnnotations(zipkinSpan, span, this.localEndpoint);
		}

		zipkinSpan.setTimestamp(span.getBegin() * 1000L);
		zipkinSpan.setDuration((span.getEnd() - span.getBegin()) * 1000L);
		zipkinSpan.setTrace_id(hash(span.getTraceId()));
		if (span.getParents().size() > 0) {
			if (span.getParents().size() > 1) {
				log.error("Zipkin doesn't support spans with multiple parents. Omitting "
						+ "other parents for " + span);
			}
			zipkinSpan.setParent_id(hash(span.getParents().get(0)));
		}
		zipkinSpan.setId(hash(span.getSpanId()));
		if (StringUtils.hasText(span.getName())) {
			zipkinSpan.setName(span.getName());
		}
		return zipkinSpan;
	}

	/**
	 * Add annotations from the sleuth Span.
	 */
	private void addZipkinAnnotations(com.twitter.zipkin.gen.Span zipkinSpan,
			Span span, Endpoint endpoint) {
		for (TimelineAnnotation ta : span.getTimelineAnnotations()) {
			Annotation zipkinAnnotation = new Annotation()
					.setHost(endpoint)
					.setTimestamp(ta.getTime() * 1000) // Zipkin is in microseconds
					.setValue(ta.getMsg());
			zipkinSpan.addToAnnotations(zipkinAnnotation);
		}
	}

	/**
	 * Creates a list of Annotations that are present in sleuth Span object.
	 *
	 * @return list of Annotations that could be added to Zipkin Span.
	 */
	private void addZipkinBinaryAnnotations(com.twitter.zipkin.gen.Span zipkinSpan,
			Span span, Endpoint endpoint) {
		for (Map.Entry<String, String> e : span.getAnnotations().entrySet()) {
			BinaryAnnotation binaryAnn = new BinaryAnnotation()
					.setAnnotation_type(AnnotationType.STRING)
					.setKey(e.getKey())
					.setValue(e.getValue().getBytes(UTF_8))
					.setHost(endpoint);
			zipkinSpan.addToBinary_annotations(binaryAnn);
		}
	}

	private static long hash(String string) {
		long h = 1125899906842597L;
		if (string == null) {
			return h;
		}
		int len = string.length();

		for (int i = 0; i < len; i++) {
			h = 31 * h + string.charAt(i);
		}
		return h;
	}

}
