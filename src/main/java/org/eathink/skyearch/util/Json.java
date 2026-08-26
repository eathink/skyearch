package org.eathink.skyearch.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Jackson 全局入口。 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static byte[] toBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize json", e);
        }
    }

    public static JsonNode readTree(byte[] content) {
        try {
            return MAPPER.readTree(content);
        } catch (Exception e) {
            throw new BadJsonException("invalid json body", e);
        }
    }

    /** 解析请求体失败时抛出, 由 HTTP 层统一转成 400。 */
    public static class BadJsonException extends RuntimeException {
        public BadJsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
