package org.eathink.skyearch.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 HTTP 路由: 按 "METHOD /path/{param}" 注册, 按段精确匹配。
 */
public final class HttpRouter {

    public record Match(ApiHandler handler, Map<String, String> params) {
    }

    private record Entry(String method, String[] pattern, ApiHandler handler) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(String method, String pattern, ApiHandler handler) {
        entries.add(new Entry(method, split(pattern), handler));
    }

    /** 找不到返回 null; 路径存在但方法不匹配时抛 405。 */
    public Match route(String method, String path) {
        String[] segments = split(path);
        boolean pathExists = false;
        for (Entry entry : entries) {
            Map<String, String> params = match(entry.pattern(), segments);
            if (params == null) {
                continue;
            }
            pathExists = true;
            if (entry.method().equals(method)) {
                return new Match(entry.handler(), params);
            }
        }
        if (pathExists) {
            throw new HttpException(405, "method not allowed: " + method + " " + path);
        }
        return null;
    }

    private static Map<String, String> match(String[] pattern, String[] segments) {
        if (pattern.length != segments.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                params.put(p.substring(1, p.length() - 1), segments[i]);
            } else if (!p.equals(segments[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        List<String> parts = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                parts.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
            }
        }
        return parts.toArray(new String[0]);
    }
}
