package org.eathink.skyearch.http;

/** 业务异常, 对应统一错误 JSON: {"code": status, "message": "..."}。 */
public class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static HttpException badRequest(String message) {
        return new HttpException(400, message);
    }

    public static HttpException notFound(String message) {
        return new HttpException(404, message);
    }

    public static HttpException conflict(String message) {
        return new HttpException(409, message);
    }
}
