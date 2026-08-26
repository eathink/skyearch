package org.eathink.skyearch.core;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneIndexTest {

    @TempDir
    Path tempDir;

    private LuceneIndex newIndex() throws IOException {
        return new LuceneIndex(tempDir.resolve("index"), new SmartChineseAnalyzer(), 16);
    }

    @Test
    void upsertSearchDeleteLifecycle() throws Exception {
        try (LuceneIndex index = newIndex()) {
            index.upsert("10001", "Java Netty 搜索引擎");
            index.upsert("10002", "Python 数据分析");
            index.upsert("10003", "Netty 网络编程");
            index.refreshBlocking();

            assertEquals(3, index.numDocs());

            // 中文分词 + AND
            SearchResult search = index.search(
                    new TermQuery(new Term(LuceneIndex.TEXT_FIELD, "搜索引擎")), 10, 0);
            assertEquals(List.of("10001"), search.ids());
            assertEquals(1, search.total());

            // upsert 覆盖同一 id
            index.upsert("10001", "Go 微服务");
            index.refreshBlocking();
            assertEquals(3, index.numDocs());
            assertEquals(0, index.search(
                    new TermQuery(new Term(LuceneIndex.TEXT_FIELD, "搜索引擎")), 10, 0).total());

            // 删除(第一次成功, 第二次返回 false 表示不存在)
            assertTrue(index.delete("10002"));
            index.refreshBlocking();
            assertFalse(index.delete("10002"));
            index.refreshBlocking();
            assertEquals(2, index.numDocs());
        }
    }

    @Test
    void persistedAcrossReopen() throws Exception {
        try (LuceneIndex index = newIndex()) {
            index.upsert("10001", "Java Netty 搜索引擎");
            index.refreshBlocking();
        }
        // 重新打开同一目录, 数据仍在
        try (LuceneIndex reopened = newIndex()) {
            reopened.refreshBlocking();
            assertEquals(1, reopened.numDocs());
            assertTrue(reopened.exists("10001"));
        }
    }

    @Test
    void offsetAndLimit() throws Exception {
        try (LuceneIndex index = newIndex()) {
            for (int i = 0; i < 5; i++) {
                index.upsert("doc-" + i, "全文 检索");
            }
            index.refreshBlocking();
            SearchResult page1 = index.search(
                    new TermQuery(new Term(LuceneIndex.TEXT_FIELD, "全文")), 2, 0);
            SearchResult page2 = index.search(
                    new TermQuery(new Term(LuceneIndex.TEXT_FIELD, "全文")), 2, 2);
            assertEquals(2, page1.ids().size());
            assertEquals(5, page1.total());
            assertEquals(2, page2.ids().size());
        }
    }

    @Test
    void emptyIndexDirectoryCreated() throws Exception {
        try (LuceneIndex index = newIndex()) {
            assertTrue(Files.isDirectory(tempDir.resolve("index")));
            assertEquals(0, index.numDocs());
        }
    }
}
