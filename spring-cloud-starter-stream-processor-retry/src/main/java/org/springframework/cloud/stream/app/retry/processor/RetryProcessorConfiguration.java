package org.springframework.cloud.stream.app.retry.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.MimeType;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.cloud.stream.app.retry.processor.RetryProcessorConfiguration.RetryProcessor;

@EnableBinding(RetryProcessor.class)
@EnableConfigurationProperties(RetryProcessorProperties.class)
public class RetryProcessorConfiguration {
    private static final String MESSAGE_GROUP_ID = RetryProcessorConfiguration.class.getSimpleName() + ".delay";
    private static final Long STARTED = System.currentTimeMillis();
    private static final String HTTP_REQUEST_URL_HEADER = "http_requestUrl";
    private static final String HTTP_REQUEST_METHOD_HEADER = "http_requestMethod";
    private static final String HTTP_STATUS_CODE_HEADER = "http_statusCode";
    private static final String RETRY_TRACE_ID = "retry_trace_id";
    private static final String RETRY_UNTIL = "retry_until";
    private static final String RETRY_COUNT = "retry_count";
    private String DELAY_EXPRESSION = "T(java.lang.Math).round(T(java.lang.Math).pow(2, headers['" + RETRY_COUNT + "']) * 1000)";

    @Autowired
    private RetryProcessorProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    private static long retryInMillis(Message<?> message) {
        Integer retryCount = message.getHeaders().get(RETRY_COUNT, Integer.class);
        return Math.round(Math.pow(2, Objects.requireNonNull(retryCount)) * 1000);
    }

    @Bean
    IntegrationFlow retryMessageFlow(RetryProcessor processor) {
        Duration retryDuration = properties.getDuration();
        return IntegrationFlows.from(processor.input())
                .enrichHeaders(h -> h.headerFunction(RETRY_TRACE_ID, m -> UUID.randomUUID().toString(), false))
                .route(Message.class, m -> {
                    MessageHeaders messageHeaders = m.getHeaders();
                    return !messageHeaders.containsKey(RETRY_UNTIL);
                }, r -> r.subFlowMapping(true,
                        f -> f.enrichHeaders(h -> h.headerFunction(RETRY_UNTIL, m -> Instant.now().plus(retryDuration).toEpochMilli(), true)
                                .header(RETRY_COUNT, 1))
                                .log(LoggingHandler.Level.INFO, message -> "Started retry for message: " + message + " in " + retryInMillis(message) + " ms")
                                .delay(MESSAGE_GROUP_ID, s -> s.delayExpression(DELAY_EXPRESSION))
                                .channel(processor.retry()))
                        .subFlowMapping(false,
                                f -> f.route(Message.class, m -> System.currentTimeMillis() < Objects.requireNonNull(m.getHeaders().get(RETRY_UNTIL, Long.class)),
                                        retryFlow -> retryFlow
                                                .subFlowMapping(true, doRetrySubFlow -> doRetrySubFlow.enrichHeaders(h -> h.headerFunction(RETRY_COUNT,
                                                        m -> Objects.requireNonNull(m.getHeaders().get(RETRY_COUNT, Integer.class)) + 1, true))
                                                        .delay(MESSAGE_GROUP_ID,
                                                                e -> e.delayExpression(DELAY_EXPRESSION))
                                                        .log(LoggingHandler.Level.INFO, message -> "Retrying message: " + message + " in " + retryInMillis(message) + " ms")
                                                        .channel(processor.retry()))
                                                .subFlowMapping(false, retryExhaustedSubFlow ->
                                                        retryExhaustedSubFlow.log(LoggingHandler.Level.INFO, message -> "Retries exhausted for message: " + message)
                                                                .handle((p, h) -> {
                                                                    HttpMethod httpMethod = HttpMethod.valueOf(Objects.requireNonNull(h.get(HTTP_REQUEST_METHOD_HEADER, String.class)));
                                                                    Integer httpStatusCode = Objects.requireNonNull(h.get(HTTP_STATUS_CODE_HEADER, Integer.class));
                                                                    ObjectNode objectNode = objectMapper.createObjectNode();
                                                                    objectNode.put("url", h.get(HTTP_REQUEST_URL_HEADER, String.class))
                                                                            .put("method", httpMethod.name())
                                                                            .put("remote_status_code", httpStatusCode)
                                                                            .put("message", "Retry exhausted for request received.");
                                                                    if (httpMethod == HttpMethod.POST && h.containsKey(MessageHeaders.CONTENT_TYPE)) {
                                                                        MimeType mimeType = h.get(MessageHeaders.CONTENT_TYPE, MimeType.class);
                                                                        MediaType mediaType = MediaType.asMediaType(mimeType);
                                                                        if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON) ||
                                                                                mediaType.isCompatibleWith(MediaType.TEXT_PLAIN) ||
                                                                                mediaType.isCompatibleWith(MediaType.TEXT_XML)) {
                                                                            return setPayload(objectNode, p);
                                                                        }
                                                                    }
                                                                    return objectNode;
                                                                })
                                                                .enrichHeaders(h -> h.headerFunction(HTTP_STATUS_CODE_HEADER, m -> HttpStatus.BAD_GATEWAY.value(), true))
                                                                .channel(processor.output()))))).get();
    }

    private JsonNode setPayload(ObjectNode objectNode, Object payload) {
        try {
            return objectNode.set("body", objectMapper.readTree((byte[]) payload));
        } catch (Exception e) {
            return objectNode.put("body", (String) payload);
        }
    }

    public interface RetryProcessor extends Processor {
        String RETRY = "retry";

        @Output(RETRY)
        MessageChannel retry();
    }
}

