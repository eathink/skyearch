package org.eathink.skyearch.core;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.eathink.skyearch.config.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 管理Bucket -> LuceneIndex 的生命周期:
 * <ul>
 *   <li>每个 Bucket 索引目录只创建一次 LuceneIndex, 长期复用</li>
 *   <li>后台定时线程按 refresh-interval-ms 调用 maybeRefresh, 让新写入可搜索</li>
 *   <li>统一 commit / close(服务关闭时)</li>
 * </ul>
 */
public final class IndexManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(IndexManager.class.getName());

    private final Analyzer analyzer;
    private final int ramBufferMb;
    private final ConcurrentHashMap<Path, LuceneIndex> indexes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refresher;

    public IndexManager(Config.Lucene config) {
        this.analyzer = createAnalyzer(config.analyzer());
        this.ramBufferMb = config.ramBufferMb();
        this.refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lucene-refresher");
            thread.setDaemon(true);
            return thread;
        });
        this.refresher.scheduleAtFixedRate(this::refreshAll,
                config.refreshIntervalMs(), config.refreshIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /** smart-chinese: SmartChineseAnalyzer(隐马尔可夫中文分词); standard: StandardAnalyzer(CJK 二元切分)。 */
    static Analyzer createAnalyzer(String name) {
        return switch (name == null ? "smart-chinese" : name.toLowerCase(Locale.ROOT)) {
            case "smart-chinese" -> new SmartChineseAnalyzer();
            case "standard" -> new StandardAnalyzer();
            default -> throw new IllegalArgumentException("unsupported analyzer: " + name);
        };
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    /** 获取(或首次创建)指定 Bucket 索引目录对应的 LuceneIndex。 */
    public LuceneIndex getOrCreate(Path indexDir) {
        return indexes.computeIfAbsent(indexDir, dir -> {
            try {
                LOG.info("opening lucene index: " + dir);
                return new LuceneIndex(dir, analyzer, ramBufferMb);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to open lucene index: " + dir, e);
            }
        });
    }

    /** 关闭并移除某个 Bucket 索引(删除 Bucket / Collection 前调用)。 */
    public void close(Path indexDir) {
        LuceneIndex index = indexes.remove(indexDir);
        if (index != null) {
            try {
                index.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "failed to close lucene index: " + indexDir, e);
            }
        }
    }

    private void refreshAll() {
        for (LuceneIndex index : indexes.values()) {
            try {
                index.maybeRefresh();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "failed to refresh lucene index: " + index.directoryPath(), e);
            }
        }
    }

    /** commit 并关闭全部索引。 */
    @Override
    public void close() {
        refresher.shutdownNow();
        for (Path dir : indexes.keySet()) {
            close(dir);
        }
    }
}
