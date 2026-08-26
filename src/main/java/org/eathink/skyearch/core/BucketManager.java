package org.eathink.skyearch.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 单个 Collection 下的 Bucket 生命周期管理。
 *
 * 目录布局:
 * <pre>
 * data/collections/{collection}/{bucket}/index
 * </pre>
 */
public final class BucketManager {

    private static final Logger LOG = Logger.getLogger(BucketManager.class.getName());

    private final String collection;
    private final Path collectionDir;
    private final IndexManager indexManager;

    public BucketManager(String collection, Path collectionDir, IndexManager indexManager) {
        this.collection = collection;
        this.collectionDir = collectionDir;
        this.indexManager = indexManager;
    }

    public String collection() {
        return collection;
    }

    public Path bucketDir(String bucket) {
        return collectionDir.resolve(bucket);
    }

    public Path bucketIndexDir(String bucket) {
        return bucketDir(bucket).resolve("index");
    }

    public boolean exists(String bucket) {
        return Files.isDirectory(bucketDir(bucket));
    }

    /** 创建 Bucket 目录(已存在返回 false)。 */
    public boolean create(String bucket) throws IOException {
        Path dir = bucketDir(bucket);
        if (Files.isDirectory(dir)) {
            return false;
        }
        Files.createDirectories(dir.resolve("index"));
        return true;
    }

    /** 获取 Bucket 索引(不存在返回 null)。 */
    public LuceneIndex getIndex(String bucket) {
        if (!exists(bucket)) {
            return null;
        }
        return indexManager.getOrCreate(bucketIndexDir(bucket));
    }

    /** 获取(必要时创建)Bucket 索引。 */
    public LuceneIndex getOrCreateIndex(String bucket) throws IOException {
        create(bucket);
        return indexManager.getOrCreate(bucketIndexDir(bucket));
    }

    /** 删除 Bucket: 关闭索引后删除目录。 */
    public boolean delete(String bucket) throws IOException {
        Path dir = bucketDir(bucket);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        indexManager.close(bucketIndexDir(bucket));
        deleteRecursively(dir);
        LOG.info("deleted bucket: " + collection + "/" + bucket);
        return true;
    }

    /** 列出该 Collection 下全部 Bucket 名。 */
    public List<String> list() throws IOException {
        List<String> buckets = new ArrayList<>();
        if (!Files.isDirectory(collectionDir)) {
            return buckets;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(collectionDir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    buckets.add(path.getFileName().toString());
                }
            }
        }
        buckets.sort(String::compareTo);
        return buckets;
    }

    static void deleteRecursively(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
