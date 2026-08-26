package org.eathink.skyearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eathink.skyearch.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路集成测试: 真实启动 Netty + Lucene, 用 JDK HttpClient 走 REST API。
 */
class SkyearchHttpTest {

    @TempDir
    Path tempDir;

    private Skyearch app;
    private HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private String base;

    @BeforeEach
    void start() throws Exception {
        Config config = new Config(
                new Config.Server("127.0.0.1", 0),
                new Config.Storage(tempDir.resolve("data").toString()),
                new Config.Lucene("smart-chinese", 16, 100),
                new Config.Search(20, 100));
        app = new Skyearch(config);
        app.start();
        base = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    @Test
    void collectionLifecycle() throws Exception {
        assertEquals(201, call("POST", "/api/v1/collections/articles", "").statusCode());
        assertEquals(409, call("POST", "/api/v1/collections/articles", "").statusCode());
        assertEquals(404, call("GET", "/api/v1/collections/missing", "").statusCode());
        assertEquals(200, call("GET", "/api/v1/collections/articles", "").statusCode());
        assertEquals(204, call("DELETE", "/api/v1/collections/articles", "").statusCode());
        assertEquals(404, call("DELETE", "/api/v1/collections/articles", "").statusCode());
    }

    @Test
    void bucketLifecycle() throws Exception {
        call("POST", "/api/v1/collections/articles", "");
        assertEquals(201, call("POST", "/api/v1/collections/articles/buckets/default", "").statusCode());
        assertEquals(409, call("POST", "/api/v1/collections/articles/buckets/default", "").statusCode());
        assertEquals(404, call("POST", "/api/v1/collections/missing/buckets/default", "").statusCode());

        JsonNode info = json(call("GET", "/api/v1/collections/articles/buckets/default", ""));
        assertEquals("default", info.get("name").asText());
        assertEquals(0, info.get("documents").asInt());

        assertEquals(204, call("DELETE", "/api/v1/collections/articles/buckets/default", "").statusCode());
        assertEquals(404, call("GET", "/api/v1/collections/articles/buckets/default", "").statusCode());
    }

    @Test
    void documentUpsertSearchDelete() throws Exception {
        // 文档写入会自动创建 collection / bucket
        assertEquals(200, call("POST", "/api/v1/collections/articles/buckets/default/documents/10001",
                "{\"text\": \"Java Netty 搜索引擎\"}").statusCode());
        call("POST", "/api/v1/collections/articles/buckets/default/documents/10005",
                "{\"text\": \"Python 数据分析\"}");
        call("POST", "/api/v1/collections/articles/buckets/default/documents/10008",
                "{\"text\": \"Netty 网络编程实战\"}");

        // 等待 refresh-interval(100ms)让新数据可搜索
        JsonNode search = awaitSearchHit("articles", "default",
                "{\"query\": \"搜索引擎\"}");
        assertEquals(1, search.get("total").asInt());
        assertEquals("10001", search.get("ids").get(0).asText());

        // AND: 同时包含 netty 和 java 的只有 10001
        JsonNode andResult = json(call("POST", "/api/v1/collections/articles/buckets/default/search",
                "{\"query\": \"Netty Java\", \"operator\": \"AND\"}"));
        assertEquals(1, andResult.get("total").asInt());

        // OR: netty 或 python -> 10001, 10005, 10008
        JsonNode orResult = json(call("POST", "/api/v1/collections/articles/buckets/default/search",
                "{\"query\": \"Netty Python\", \"operator\": \"OR\", \"limit\": 10}"));
        assertEquals(3, orResult.get("total").asInt());

        // limit / offset
        JsonNode paged = json(call("POST", "/api/v1/collections/articles/buckets/default/search",
                "{\"query\": \"Netty Python\", \"operator\": \"OR\", \"limit\": 2, \"offset\": 2}"));
        assertEquals(3, paged.get("total").asInt());
        assertEquals(1, paged.get("ids").size());

        // 搜索不存在的 bucket
        assertEquals(404, call("POST", "/api/v1/collections/articles/buckets/nope/search",
                "{\"query\": \"x\"}").statusCode());

        // 删除文档(第一次成功, 第二次返回 404)
        assertEquals(204, call("DELETE", "/api/v1/collections/articles/buckets/default/documents/10005",
                "").statusCode());
        awaitDocDeleted("articles", "default", "10005");
        assertEquals(404, call("DELETE", "/api/v1/collections/articles/buckets/default/documents/10005",
                "").statusCode());
    }

    /** 等待 refresh 让删除可见后再断言 404(避免 NRT 可见性延迟导致的偶发失败)。 */
    private void awaitDocDeleted(String collection, String bucket, String id) throws Exception {
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> response = call("DELETE",
                    "/api/v1/collections/" + collection + "/buckets/" + bucket + "/documents/" + id, "");
            if (response.statusCode() == 404) {
                return;
            }
            Thread.sleep(100);
        }
    }

    @Test
    void badRequests() throws Exception {
        call("POST", "/api/v1/collections/articles/buckets/default/documents/1",
                "{\"text\": \"hello\"}");
        // 无效 JSON
        assertEquals(400, call("POST", "/api/v1/collections/articles/buckets/default/documents/2",
                "{not-json").statusCode());
        // 缺少 text
        assertEquals(400, call("POST", "/api/v1/collections/articles/buckets/default/documents/2",
                "{\"other\": 1}").statusCode());
        // 非法 collection 名(含路径穿越字符)
        assertEquals(400, call("POST", "/api/v1/collections/a..b/buckets/b/documents/1",
                "{\"text\": \"x\"}").statusCode());
        // 非法 operator
        call("POST", "/api/v1/collections/articles/buckets/default/documents/3", "{\"text\": \"y\"}");
        assertEquals(400, call("POST", "/api/v1/collections/articles/buckets/default/search",
                "{\"query\": \"y\", \"operator\": \"XOR\"}").statusCode());
        // 负 offset
        assertEquals(400, call("POST", "/api/v1/collections/articles/buckets/default/search",
                "{\"query\": \"y\", \"offset\": -1}").statusCode());
        // 未知路由
        assertEquals(404, call("GET", "/api/v1/unknown", "").statusCode());
        // 错误响应统一格式
        JsonNode error = json(call("GET", "/api/v1/unknown", ""));
        assertEquals(404, error.get("code").asInt());
        assertTrue(error.has("message"));
    }

    /** refresh-interval 之前新写入可能不可见, 轮询直到可见。 */
    private JsonNode awaitSearchHit(String collection, String bucket, String body) throws Exception {
        HttpResponse<String> response = null;
        for (int i = 0; i < 50; i++) {
            response = call("POST", "/api/v1/collections/" + collection + "/buckets/" + bucket + "/search", body);
            if (json(response).get("total").asLong() > 0) {
                return json(response);
            }
            Thread.sleep(100);
        }
        return json(response);
    }

    @Test
    void dataSurvivesRestart() throws Exception {
        call("POST", "/api/v1/collections/articles/buckets/default/documents/10001",
                "{\"text\": \"Java Netty 搜索引擎\"}");
        awaitSearchHit("articles", "default", "{\"query\": \"搜索引擎\"}");

        // 关闭(commit + close), 用同一数据目录重启
        app.close();
        Config config = new Config(
                new Config.Server("127.0.0.1", 0),
                new Config.Storage(tempDir.resolve("data").toString()),
                new Config.Lucene("smart-chinese", 16, 100),
                new Config.Search(20, 100));
        app = new Skyearch(config);
        app.start();
        base = "http://127.0.0.1:" + app.port();

        JsonNode result = awaitSearchHit("articles", "default", "{\"query\": \"搜索引擎\"}");
        assertEquals("10001", result.get("ids").get(0).asText());
        JsonNode info = json(call("GET", "/api/v1/collections/articles/buckets/default", ""));
        assertEquals(1, info.get("documents").asInt());
    }
}
