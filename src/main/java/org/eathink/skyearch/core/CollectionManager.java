package org.eathink.skyearch.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Collection 生命周期管理。
 * 启动时扫描 data/collections 目录, 自动加载已有 Collection / Bucket(即已有 Lucene 索引)。
 */
public final class CollectionManager {

    private static final Logger LOG = Logger.getLogger(CollectionManager.class.getName());

    private final Path collectionsRoot;
    private final IndexManager indexManager;
    private final ConcurrentHashMap<String, BucketManager> collections = new ConcurrentHashMap<>();

    public CollectionManager(Path collectionsRoot, IndexManager indexManager) throws IOException {
        this.collectionsRoot = collectionsRoot;
        this.indexManager = indexManager;
        Files.createDirectories(collectionsRoot);
        loadExisting();
    }

    private void loadExisting() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(collectionsRoot)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    String name = path.getFileName().toString();
                    collections.put(name, new BucketManager(name, path, indexManager));
                    LOG.info("loaded collection: " + name);
                }
            }
        }
    }

    public Path collectionsRoot() {
        return collectionsRoot;
    }

    public boolean exists(String collection) {
        return collections.containsKey(collection);
    }

    public BucketManager get(String collection) {
        return collections.get(collection);
    }

    /** 获取或创建 Collection。 */
    public BucketManager getOrCreate(String collection) throws IOException {
        BucketManager existing = collections.get(collection);
        if (existing != null) {
            return existing;
        }
        Path dir = collectionsRoot.resolve(collection);
        Files.createDirectories(dir);
        BucketManager created = new BucketManager(collection, dir, indexManager);
        BucketManager winner = collections.putIfAbsent(collection, created);
        if (winner != null) {
            return winner;
        }
        LOG.info("created collection: " + collection);
        return created;
    }

    /** 删除 Collection: 关闭其下全部 Bucket 索引并删除目录。 */
    public boolean delete(String collection) throws IOException {
        BucketManager removed = collections.remove(collection);
        if (removed == null) {
            return false;
        }
        for (String bucket : removed.list()) {
            indexManager.close(removed.bucketIndexDir(bucket));
        }
        BucketManager.deleteRecursively(collectionsRoot.resolve(collection));
        LOG.info("deleted collection: " + collection);
        return true;
    }

    public List<String> list() {
        List<String> names = new ArrayList<>(collections.keySet());
        names.sort(String::compareTo);
        return names;
    }
}
