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

package org.springframework.cloud.sleuth.instrument.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.sleuth.Trace;
import org.springframework.cloud.sleuth.TraceManager;
import org.springframework.cloud.sleuth.instrument.integration.TraceContextPropagationChannelInterceptorTests.App;
import org.springframework.cloud.sleuth.sampler.AlwaysSampler;
import org.springframework.cloud.sleuth.trace.TraceContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author Spencer Gibb
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes=App.class)
@IntegrationTest
@DirtiesContext
public class TraceContextPropagationChannelInterceptorTests {

	@Autowired
	@Qualifier("channel")
	private PollableChannel channel;

	@Autowired
	private TraceManager traceManager;

	@After
	public void close() {
		TraceContextHolder.removeCurrentTrace();
	}

	@Test
	public void testSpanPropagation() {

		Trace trace = this.traceManager.startSpan("testSendMessage", new AlwaysSampler(), null);
		this.channel.send(MessageBuilder.withPayload("hi").build());
		String expectedSpanId = trace.getSpan().getSpanId();
		this.traceManager.close(trace);

		Message<?> message = this.channel.receive(0);

		assertNotNull("message was null", message);

		String spanId = message.getHeaders().get(Trace.SPAN_ID_NAME, String.class);
		assertEquals("spanId was wrong", expectedSpanId,  spanId);

		String traceId = message.getHeaders().get(Trace.TRACE_ID_NAME, String.class);
		assertNotNull("traceId was null", traceId);
	}

	@Configuration
	@EnableAutoConfiguration
	static class App {

		@Bean
		public QueueChannel channel() {
			return new QueueChannel();
		}

	}
}
