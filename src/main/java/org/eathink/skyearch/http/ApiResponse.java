package org.eathink.skyearch.http;

/** 业务处理结果: status + 可序列化为 JSON 的 body(204 时为 null)。 */
public record ApiResponse(int status, Object body) {

    public static ApiResponse ok(Object body) {
        return new ApiResponse(200, body);
    }

    public static ApiResponse created(Object body) {
        return new ApiResponse(201, body);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, null);
    }
}
