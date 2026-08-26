package org.eathink.skyearch.handler;

import org.eathink.skyearch.core.BucketManager;
import org.eathink.skyearch.core.CollectionManager;
import org.eathink.skyearch.http.ApiHandler;
import org.eathink.skyearch.http.ApiResponse;
import org.eathink.skyearch.http.HttpException;
import org.eathink.skyearch.util.Names;

import java.util.LinkedHashMap;
import java.util.Map;

/** /api/v1/collections/{collection}/buckets/{bucket} 生命周期管理。 */
public final class BucketHandler {

    private final CollectionManager collections;

    public final ApiHandler create;
    public final ApiHandler get;
    public final ApiHandler delete;

    public BucketHandler(CollectionManager collections) {
        this.collections = collections;
        this.create = request -> {
            String collection = request.param("collection");
            String bucket = request.param("bucket");
            Names.checkName(collection, "collection");
            Names.checkName(bucket, "bucket");
            BucketManager buckets = requireCollection(collection);
            if (!buckets.create(bucket)) {
                throw HttpException.conflict("bucket already exists: " + collection + "/" + bucket);
            }
            return ApiResponse.created(Map.of("collection", collection, "name", bucket));
        };
        this.get = request -> {
            String collection = request.param("collection");
            String bucket = request.param("bucket");
            Names.checkName(collection, "collection");
            Names.checkName(bucket, "bucket");
            BucketManager buckets = requireCollection(collection);
            if (!buckets.exists(bucket)) {
                throw HttpException.notFound("bucket not found: " + collection + "/" + bucket);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("collection", collection);
            body.put("name", bucket);
            body.put("documents", CollectionHandler.documentsIn(buckets, bucket));
            return ApiResponse.ok(body);
        };
        this.delete = request -> {
            String collection = request.param("collection");
            String bucket = request.param("bucket");
            Names.checkName(collection, "collection");
            Names.checkName(bucket, "bucket");
            BucketManager buckets = requireCollection(collection);
            if (!buckets.delete(bucket)) {
                throw HttpException.notFound("bucket not found: " + collection + "/" + bucket);
            }
            return ApiResponse.noContent();
        };
    }

    private BucketManager requireCollection(String collection) {
        BucketManager buckets = collections.get(collection);
        if (buckets == null) {
            throw HttpException.notFound("collection not found: " + collection);
        }
        return buckets;
    }
}
