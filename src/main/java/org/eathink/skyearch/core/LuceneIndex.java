package org.eathink.skyearch.core;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一个 Bucket 对应一个独立 Lucene 索引。
 *
 * <ul>
 *   <li>id:   StringField, Store.YES —— 搜索服务最重要的数据, 命中后返回给调用方</li>
 *   <li>text: TextField,   Store.NO  —— 只建全文索引, 不保存原始文本</li>
 * </ul>
 *
 * IndexWriter 全生命周期复用; SearcherManager 基于 IndexWriter 的 NRT reader,
 * 未 commit 的新写入在 maybeRefresh 之后即可被搜索(可见性由 refresh-interval-ms 控制)。
 */
public final class LuceneIndex implements AutoCloseable {

    public static final String ID_FIELD = "id";
    public static final String TEXT_FIELD = "text";

    private final Path directoryPath;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;

    public LuceneIndex(Path indexDir, Analyzer analyzer, int ramBufferMb) throws IOException {
        this.directoryPath = indexDir;
        this.directory = new NIOFSDirectory(indexDir);
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setRAMBufferSizeMB(ramBufferMb);
        this.writer = new IndexWriter(directory, config);
        this.searchers = new SearcherManager(writer, true, false, new SearcherFactory());
    }

    /** 新增或覆盖写入一个文档(按 id upsert)。 */
    public void upsert(String id, String text) throws IOException {
        Document doc = new Document();
        doc.add(new StringField(ID_FIELD, id, Field.Store.YES));
        doc.add(new TextField(TEXT_FIELD, text, Field.Store.NO));
        writer.updateDocument(new Term(ID_FIELD, id), doc);
    }

    public boolean exists(String id) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            return searcher.count(new TermQuery(new Term(ID_FIELD, id))) > 0;
        } finally {
            searchers.release(searcher);
        }
    }

    /** 删除指定文档; 文档不存在时返回 false。 */
    public boolean delete(String id) throws IOException {
        if (!exists(id)) {
            return false;
        }
        writer.deleteDocuments(new Term(ID_FIELD, id));
        return true;
    }

    /** 分页搜索, 只返回 Document ID 列表与命中总数。 */
    public SearchResult search(Query query, int limit, int offset) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            // totalHitsThreshold = MAX_VALUE 保证 total 是精确值而非下界
            TopScoreDocCollector collector = TopScoreDocCollector.create(offset + limit, Integer.MAX_VALUE);
            searcher.search(query, collector);
            TopDocs top = collector.topDocs(offset, offset + limit);
            StoredFields storedFields = searcher.storedFields();
            List<String> ids = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                Document doc = storedFields.document(scoreDoc.doc, Set.of(ID_FIELD));
                ids.add(doc.get(ID_FIELD));
            }
            return new SearchResult(ids, top.totalHits.value);
        } finally {
            searchers.release(searcher);
        }
    }

    /** 当前索引中的文档数(包含未 commit 的 NRT 写入)。 */
    public int numDocs() throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searchers.release(searcher);
        }
    }

    /** 定时任务调用: 非阻塞刷新, 尝试让新写入可搜索。 */
    public void maybeRefresh() throws IOException {
        searchers.maybeRefresh();
    }

    /** 阻塞刷新(测试 / 手动触发用)。 */
    public void refreshBlocking() throws IOException {
        searchers.maybeRefreshBlocking();
    }

    public Path directoryPath() {
        return directoryPath;
    }

    /** commit 并关闭全部资源。 */
    @Override
    public void close() throws IOException {
        searchers.close();
        writer.commit();
        writer.close();
        directory.close();
    }

    public static UncheckedIOException io(IOException e) {
        throw new UncheckedIOException(e);
    }
}
