package org.eathink.skyearch;

import org.eathink.skyearch.config.Config;
import org.eathink.skyearch.core.CollectionManager;
import org.eathink.skyearch.core.IndexManager;
import org.eathink.skyearch.handler.BucketHandler;
import org.eathink.skyearch.handler.CollectionHandler;
import org.eathink.skyearch.handler.DocumentHandler;
import org.eathink.skyearch.handler.SearchHandler;
import org.eathink.skyearch.handler.SearchService;
import org.eathink.skyearch.http.HttpRouter;
import org.eathink.skyearch.server.NettyServer;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * 组装入口: Config -> IndexManager / CollectionManager -> Handlers -> Router -> NettyServer。
 *
// * 优雅关闭顺序: 停止接收请求(Netty) -> commit IndexWriter -> 关闭 IndexWriter / Directory -> 释放资源。
 */
public final class Skyearch implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Skyearch.class.getName());

    private final IndexManager indexManager;
    private final CollectionManager collectionManager;
    private final NettyServer server;
    private volatile boolean closed;

    public Skyearch(Config config) throws Exception {
        config.validate();
        this.indexManager = new IndexManager(config.lucene());
        this.collectionManager = new CollectionManager(
                Path.of(config.storage().dataPath()).resolve("collections"), indexManager);
        this.server = new NettyServer(config.server(), buildRouter(config));
    }

    private HttpRouter buildRouter(Config config) {
        CollectionHandler collections = new CollectionHandler(collectionManager);
        BucketHandler buckets = new BucketHandler(collectionManager);
        DocumentHandler documents = new DocumentHandler(collectionManager);
        SearchHandler search = new SearchHandler(
                collectionManager, new SearchService(indexManager.analyzer()), config.search());

        HttpRouter router = new HttpRouter();
        router.add("POST", "/api/v1/collections/{collection}", collections.create);
        router.add("GET", "/api/v1/collections/{collection}", collections.get);
        router.add("DELETE", "/api/v1/collections/{collection}", collections.delete);

        router.add("POST", "/api/v1/collections/{collection}/buckets/{bucket}", buckets.create);
        router.add("GET", "/api/v1/collections/{collection}/buckets/{bucket}", buckets.get);
        router.add("DELETE", "/api/v1/collections/{collection}/buckets/{bucket}", buckets.delete);

        router.add("POST", "/api/v1/collections/{collection}/buckets/{bucket}/documents/{id}", documents.upsert);
        router.add("DELETE", "/api/v1/collections/{collection}/buckets/{bucket}/documents/{id}", documents.delete);

        router.add("POST", "/api/v1/collections/{collection}/buckets/{bucket}/search", search.search);
        return router;
    }

    public void start() throws InterruptedException {
        server.start();
    }

    public int port() {
        return server.port();
    }

    public void awaitShutdown() throws InterruptedException {
        server.awaitShutdown();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 1. 停止接收请求 2. commit + close 全部 Lucene 索引 3. 释放 Netty 线程组
        server.stop();
        indexManager.close();
        LOG.info("skyearch closed");
    }

    public static void main(String[] args) throws Exception {
        Path configPath;

        if (args.length > 0) {
            configPath = Path.of(args[0]);
        } else {
            URL resource = Skyearch.class
                    .getClassLoader()
                    .getResource("application.yml");

            if (resource == null) {
                throw new RuntimeException("Missing application.yml");
            }

            configPath = Paths.get(resource.toURI());
        }
        Config config = Config.load(configPath);

        Skyearch app = new Skyearch(config);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "skyearch-shutdown"));
        app.start();
        app.awaitShutdown();
    }
}
