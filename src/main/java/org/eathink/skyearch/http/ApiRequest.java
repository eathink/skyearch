package org.eathink.skyearch.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** 路由匹配后的请求(已做 URL 解码)。body 为已解析的 JSON, 无 body 时为 null。 */
public record ApiRequest(String method, String path, Map<String, String> params, JsonNode body) {

    public String param(String name) {
        return params.get(name);
    }

    /** 读取必填的字符串字段。 */
    public String requiredText(String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw HttpException.badRequest("field '" + field + "' is required and must be a non-empty string");
        }
        return node.asText();
    }
}
