package org.eathink.skyearch.handler;

import org.eathink.skyearch.core.BucketManager;
import org.eathink.skyearch.core.CollectionManager;
import org.eathink.skyearch.core.LuceneIndex;
import org.eathink.skyearch.http.ApiHandler;
import org.eathink.skyearch.http.ApiResponse;
import org.eathink.skyearch.http.HttpException;
import org.eathink.skyearch.util.Names;

import java.io.IOException;
import java.util.Map;

/** 文档写入与删除(按 Document ID, 文本只建索引不保存)。 */
public final class DocumentHandler {

    private final CollectionManager collections;

    public final ApiHandler upsert;
    public final ApiHandler delete;

    public DocumentHandler(CollectionManager collections) {
        this.collections = collections;
        // POST .../documents/{id}: 获取或创建 Collection / Bucket, upsert 文档
        this.upsert = request -> {
            String collection = request.param("collection");
            String bucket = request.param("bucket");
            String id = request.param("id");
            Names.checkName(collection, "collection");
            Names.checkName(bucket, "bucket");
            Names.checkDocId(id);
            String text = request.requiredText("text");

            BucketManager buckets = collections.getOrCreate(collection);
            LuceneIndex index = buckets.getOrCreateIndex(bucket);
            try {
                index.upsert(id, text);
            } catch (IOException e) {
                throw new RuntimeException("failed to index document: " + id, e);
            }
            return ApiResponse.ok(Map.of("id", id));
        };
        // DELETE .../documents/{id}
        this.delete = request -> {
            String collection = request.param("collection");
            String bucket = request.param("bucket");
            String id = request.param("id");
            Names.checkName(collection, "collection");
            Names.checkName(bucket, "bucket");
            Names.checkDocId(id);

            LuceneIndex index = requireIndex(collection, bucket);
            boolean deleted;
            try {
                deleted = index.delete(id);
            } catch (IOException e) {
                throw new RuntimeException("failed to delete document: " + id, e);
            }
            if (!deleted) {
                throw HttpException.notFound("document not found: " + id);
            }
            return ApiResponse.noContent();
        };
    }

    private LuceneIndex requireIndex(String collection, String bucket) {
        BucketManager buckets = collections.get(collection);
        if (buckets == null) {
            throw HttpException.notFound("collection not found: " + collection);
        }
        LuceneIndex index = buckets.getIndex(bucket);
        if (index == null) {
            throw HttpException.notFound("bucket not found: " + collection + "/" + bucket);
        }
        return index;
    }
}
