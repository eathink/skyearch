package org.eathink.skyearch.http;

import org.junit.jupiter.api.Test;
import org.eathink.skyearch.util.Json;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRouterTest {

    @Test
    void matchWithParams() throws Exception {
        HttpRouter router = new HttpRouter();
        router.add("POST", "/api/v1/collections/{collection}/buckets/{bucket}/documents/{id}",
                request -> ApiResponse.ok(Map.of("id", request.param("id"))));

        HttpRouter.Match match = router.route("POST", "/api/v1/collections/articles/buckets/default/documents/10001");
        assertEquals("10001", match.params().get("id"));
        assertEquals("articles", match.params().get("collection"));
        assertEquals("default", match.params().get("bucket"));

        com.fasterxml.jackson.databind.JsonNode body =
                Json.readTree(Json.toBytes(Map.of("text", "hello")));
        ApiResponse response = match.handler().handle(
                new ApiRequest("POST", "/x", match.params(), body));
        assertEquals(200, response.status());
    }

    @Test
    void methodMismatchThrows405() {
        HttpRouter router = new HttpRouter();
        router.add("GET", "/api/v1/collections/{collection}", request -> ApiResponse.ok(null));
        assertThrows(HttpException.class, () -> router.route("DELETE", "/api/v1/collections/articles"));
        HttpException e = assertThrows(HttpException.class,
                () -> router.route("DELETE", "/api/v1/collections/articles"));
        assertEquals(405, e.status());
    }

    @Test
    void unknownPathReturnsNull() {
        HttpRouter router = new HttpRouter();
        router.add("GET", "/api/v1/collections/{collection}", request -> ApiResponse.ok(null));
        assertNull(router.route("GET", "/api/v1/nothing"));
    }
}
