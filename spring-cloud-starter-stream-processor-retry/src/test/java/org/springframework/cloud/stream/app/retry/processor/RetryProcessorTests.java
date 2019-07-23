package org.springframework.cloud.stream.app.retry.processor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.MimeType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertThat;
import static org.springframework.cloud.stream.app.retry.processor.RetryProcessorConfiguration.RetryProcessor;
import static org.springframework.cloud.stream.test.matcher.MessageQueueMatcher.receivesMessageThat;
import static org.springframework.integration.test.matcher.HeaderMatcher.hasHeader;
import static org.springframework.integration.test.matcher.HeaderMatcher.hasHeaderKey;
import static org.springframework.integration.test.matcher.PayloadMatcher.hasPayload;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
public abstract class RetryProcessorTests {

    @Autowired
    protected RetryProcessor channels;

    @Autowired
    protected MessageCollector messageCollector;

    @TestPropertySource(properties = {"retry.duration=24H"})
    public static class RetryMessageTests extends RetryProcessorTests {

        @Test
        public void testRetryNew() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1234");
            map.put("http_requestMethod", "GET");
            map.put("http_requestUrl", "http://some.domain/get?foo=1&bar=2");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage("Test Payload", messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.retry()),
                    receivesMessageThat(allOf(hasHeader("http_requestMethod", "GET"),
                            hasHeaderKey("retry_trace_id"),
                            hasHeader("retry_count", 1),
                            hasHeaderKey("retry_until"))));
        }

        @Test
        public void testRetryAgain() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1234");
            map.put("http_requestMethod", "GET");
            map.put("http_requestUrl", "http://some.domain/get?foo=1&bar=2");
            map.put("http_statusCode", HttpStatus.TOO_MANY_REQUESTS.value());
            map.put("retry_until", Instant.now().plus(Duration.ofMillis(5000)).toEpochMilli());
            map.put("retry_count", 1);
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage("Test Payload", messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.retry()),
                    receivesMessageThat(allOf(hasHeader("http_requestMethod", "GET"),
                            hasHeaderKey("retry_trace_id"),
                            hasHeader("retry_count", 2),
                            hasHeaderKey("retry_until"))));
        }

        @Test
        public void testRetryExhaustedPayloadString() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1234");
            map.put("http_requestMethod", "POST");
            map.put("http_requestUrl", "http://some.domain/");
            map.put("http_statusCode", HttpStatus.TOO_MANY_REQUESTS.value());
            map.put("retry_until", 1546268400000L);
            map.put("retry_count", 1);
            map.put(MessageHeaders.CONTENT_TYPE, MimeType.valueOf("application/json"));
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage("Test Payload", messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesMessageThat(allOf(hasHeader("http_requestMethod", "POST"),
                            hasHeaderKey("retry_trace_id"),
                            hasHeader("retry_count", 1),
                            hasHeaderKey("retry_until"),
                            hasPayload(isA(String.class)))));
        }

        @Test
        public void testRetryExhaustedPayloadJson() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1234");
            map.put("http_requestMethod", "POST");
            map.put("http_requestUrl", "http://some.domain/");
            map.put("http_statusCode", HttpStatus.TOO_MANY_REQUESTS.value());
            map.put("retry_until", 1546268400000L);
            map.put("retry_count", 1);
            map.put(MessageHeaders.CONTENT_TYPE, MimeType.valueOf("application/json"));
            MessageHeaders messageHeaders = new MessageHeaders(map);
            String json = "{\n" +
                    "    \"glossary\": {\n" +
                    "        \"title\": \"example glossary\",\n" +
                    "\t\t\"GlossDiv\": {\n" +
                    "            \"title\": \"S\",\n" +
                    "\t\t\t\"GlossList\": {\n" +
                    "                \"GlossEntry\": {\n" +
                    "                    \"ID\": \"SGML\",\n" +
                    "\t\t\t\t\t\"SortAs\": \"SGML\",\n" +
                    "\t\t\t\t\t\"GlossTerm\": \"Standard Generalized Markup Language\",\n" +
                    "\t\t\t\t\t\"Acronym\": \"SGML\",\n" +
                    "\t\t\t\t\t\"Abbrev\": \"ISO 8879:1986\",\n" +
                    "\t\t\t\t\t\"GlossDef\": {\n" +
                    "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\",\n" +
                    "\t\t\t\t\t\t\"GlossSeeAlso\": [\"GML\", \"XML\"]\n" +
                    "                    },\n" +
                    "\t\t\t\t\t\"GlossSee\": \"markup\"\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +
                    "}";
            Message message = MessageBuilder.createMessage(json.getBytes(), messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesMessageThat(allOf(
                            hasHeader("http_requestMethod", "POST"),
                            hasHeaderKey("retry_trace_id"),
                            hasHeader("retry_count", 1),
                            hasHeaderKey("retry_until"),
                            hasPayload(isA(String.class)))));
        }

        @Test
        public void testRetryExhaustedPayloadXml() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1234");
            map.put("http_requestMethod", "POST");
            map.put("http_requestUrl", "http://some.domain/");
            map.put("http_statusCode", HttpStatus.TOO_MANY_REQUESTS.value());
            map.put("retry_until", 1546268400000L);
            map.put("retry_count", 1);
            map.put(MessageHeaders.CONTENT_TYPE, MimeType.valueOf("text/xml"));
            MessageHeaders messageHeaders = new MessageHeaders(map);
            String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>  \n" +
                    "<note>  \n" +
                    "  <to>Tove</to>  \n" +
                    "  <from>Jani</from>  \n" +
                    "  <heading>Reminder</heading>  \n" +
                    "  <body>Don't forget me this weekend!</body>  \n" +
                    "</note>  ";
            Message message = MessageBuilder.createMessage(xml, messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesMessageThat(allOf(hasHeader("http_requestMethod", "POST"),
                            hasHeaderKey("retry_trace_id"),
                            hasHeader("retry_count", 1),
                            hasHeaderKey("retry_until"),
                            hasPayload(isA(String.class)))));
        }
    }

    @SpringBootApplication
    public static class TestRetryProcessorApplication {

    }
}
