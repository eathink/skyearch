package org.eathink.skyearch.handler;

import org.eathink.skyearch.core.BucketManager;
import org.eathink.skyearch.core.CollectionManager;
import org.eathink.skyearch.http.ApiHandler;
import org.eathink.skyearch.http.ApiResponse;
import org.eathink.skyearch.http.HttpException;
import org.eathink.skyearch.util.Names;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /api/v1/collections/{collection} 生命周期管理。 */
public final class CollectionHandler {

    private final CollectionManager collections;

    /** POST /api/v1/collections/{collection} */
    public final ApiHandler create;
    /** GET /api/v1/collections/{collection} */
    public final ApiHandler get;
    /** DELETE /api/v1/collections/{collection} */
    public final ApiHandler delete;

    public CollectionHandler(CollectionManager collections) {
        this.collections = collections;
        this.create = request -> {
            String name = request.param("collection");
            Names.checkName(name, "collection");
            if (collections.exists(name)) {
                throw HttpException.conflict("collection already exists: " + name);
            }
            collections.getOrCreate(name);
            return ApiResponse.created(Map.of("name", name));
        };
        this.get = request -> {
            String name = request.param("collection");
            Names.checkName(name, "collection");
            BucketManager buckets = collections.get(name);
            if (buckets == null) {
                throw HttpException.notFound("collection not found: " + name);
            }
            List<String> bucketNames = buckets.list();
            long documents = 0;
            List<Map<String, Object>> bucketInfos = new ArrayList<>();
            for (String bucket : bucketNames) {
                long count = documentsIn(buckets, bucket);
                documents += count;
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", bucket);
                info.put("documents", count);
                bucketInfos.add(info);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("buckets", bucketInfos);
            body.put("documents", documents);
            return ApiResponse.ok(body);
        };
        this.delete = request -> {
            String name = request.param("collection");
            Names.checkName(name, "collection");
            if (!collections.delete(name)) {
                throw HttpException.notFound("collection not found: " + name);
            }
            return ApiResponse.noContent();
        };
    }

    static long documentsIn(BucketManager buckets, String bucket) {
        try {
            var index = buckets.getIndex(bucket);
            return index == null ? 0 : index.numDocs();
        } catch (IOException e) {
            throw new RuntimeException("failed to count documents of bucket: " + bucket, e);
        }
    }
}
