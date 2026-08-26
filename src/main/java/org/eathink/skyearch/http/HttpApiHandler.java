package org.eathink.skyearch.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import org.eathink.skyearch.util.Json;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP 入口: 解析 FullHttpRequest -> 路由 -> Handler -> 统一 JSON 响应。
 * 该 handler 运行在独立的业务线程组(DefaultEventExecutorGroup)上, 阻塞的 Lucene 调用不会卡住 I/O 事件循环。
 */
public final class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(HttpApiHandler.class.getName());
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HttpRouter router;

    public HttpApiHandler(HttpRouter router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        try {
            String uri = request.uri();
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            String method = request.method().name();

            HttpRouter.Match match = router.route(method, path);
            if (match == null) {
                throw HttpException.notFound("no such route: " + method + " " + path);
            }

            ApiRequest apiRequest = new ApiRequest(method, path, match.params(), readBody(request));
            ApiResponse response = match.handler().handle(apiRequest);
            write(ctx, response.status(), response.body(), keepAlive);
        } catch (HttpException e) {
            write(ctx, e.status(), Map.of("code", e.status(), "message", String.valueOf(e.getMessage())), keepAlive);
        } catch (Json.BadJsonException e) {
            write(ctx, 400, Map.of("code", 400, "message", e.getMessage()), keepAlive);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "unhandled error processing request: " + request.uri(), e);
            write(ctx, 500, Map.of("code", 500, "message", "internal server error"), keepAlive);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readBody(FullHttpRequest request) {
        ByteBuf content = request.content();
        if (content.readableBytes() == 0) {
            return null;
        }
        if (content.readableBytes() > MAX_BODY_BYTES) {
            throw HttpException.badRequest("request body too large");
        }
        byte[] bytes = new byte[content.readableBytes()];
        content.readBytes(bytes);
        return Json.readTree(bytes);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "connection error", cause);
        ctx.close();
    }

    private static void write(ChannelHandlerContext ctx, int status, Object body, boolean keepAlive) {
        byte[] bytes = body == null ? new byte[0] : Json.toBytes(body);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        HttpUtil.setContentLength(response, bytes.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        HttpUtil.setKeepAlive(response, keepAlive);
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
