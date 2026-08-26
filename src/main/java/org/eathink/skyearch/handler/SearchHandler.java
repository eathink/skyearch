package org.eathink.skyearch.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.eathink.skyearch.config.Config;
import org.eathink.skyearch.core.BucketManager;
import org.eathink.skyearch.core.CollectionManager;
import org.eathink.skyearch.core.LuceneIndex;
import org.eathink.skyearch.core.SearchResult;
import org.eathink.skyearch.http.ApiHandler;
import org.eathink.skyearch.http.ApiRequest;
import org.eathink.skyearch.http.ApiResponse;
import org.eathink.skyearch.http.HttpException;
import org.eathink.skyearch.util.Names;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** POST .../search: 全文搜索, 只返回 Document ID。 */
public final class SearchHandler {

    private final CollectionManager collections;
    private final SearchService searchService;
    private final Config.Search searchConfig;
    public final ApiHandler search;

    public SearchHandler(CollectionManager collections, SearchService searchService, Config.Search searchConfig) {
        this.collections = collections;
        this.searchService = searchService;
        this.searchConfig = searchConfig;
        this.search = request -> {
            String collection = request.param("collection");
            String bucket = request.param("bucket");
            Names.checkName(collection, "collection");
            Names.checkName(bucket, "bucket");

            String query = request.requiredText("query");
            SearchService.Operator operator = SearchService.parseOperator(text(request, "operator"));
            int limit = intField(request, "limit", searchConfig.defaultLimit());
            int offset = intField(request, "offset", 0);
            if (limit <= 0) {
                throw HttpException.badRequest("limit must be > 0");
            }
            if (limit > searchConfig.maxLimit()) {
                limit = searchConfig.maxLimit();
            }
            if (offset < 0) {
                throw HttpException.badRequest("offset must be >= 0");
            }

            BucketManager buckets = collections.get(collection);
            if (buckets == null) {
                throw HttpException.notFound("collection not found: " + collection);
            }
            LuceneIndex index = buckets.getIndex(bucket);
            if (index == null) {
                throw HttpException.notFound("bucket not found: " + collection + "/" + bucket);
            }

            SearchResult result;
            try {
                result = searchService.search(index, query, operator, limit, offset);
            } catch (IOException e) {
                throw new RuntimeException("search failed", e);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", result.ids());
            body.put("total", result.total());
            return ApiResponse.ok(body);
        };
    }

    private static String text(ApiRequest request, String field) {
        JsonNode node = request.body() == null ? null : request.body().get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static int intField(ApiRequest request, String field, int defaultValue) {
        JsonNode node = request.body() == null ? null : request.body().get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isInt()) {
            throw HttpException.badRequest("field '" + field + "' must be an integer");
        }
        return node.asInt();
    }
}
