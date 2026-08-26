package org.eathink.skyearch.handler;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.eathink.skyearch.core.LuceneIndex;
import org.eathink.skyearch.core.SearchResult;
import org.eathink.skyearch.http.HttpException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 搜索逻辑: 用与写入相同的 Analyzer 对 query 分词, 组成 AND / OR 的 BooleanQuery。
 * 不引入 QueryParser, 不做 phrase / fuzzy 等复杂语法。
 */
public final class SearchService {

    public enum Operator {AND, OR}

    private final Analyzer analyzer;

    public SearchService(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    public SearchResult search(LuceneIndex index, String query, Operator operator, int limit, int offset)
            throws IOException {
        Query luceneQuery = buildQuery(query, operator);
        if (luceneQuery == null) {
            return new SearchResult(new ArrayList<>(), 0);
        }
        return index.search(luceneQuery, limit, offset);
    }

    Query buildQuery(String query, Operator operator) throws IOException {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return null;
        }
        if (tokens.size() == 1) {
            return new TermQuery(new Term(LuceneIndex.TEXT_FIELD, tokens.iterator().next()));
        }
        BooleanClause.Occur occur = operator == Operator.AND
                ? BooleanClause.Occur.MUST
                : BooleanClause.Occur.SHOULD;
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String token : tokens) {
            builder.add(new TermQuery(new Term(LuceneIndex.TEXT_FIELD, token)), occur);
        }
        if (operator == Operator.OR) {
            builder.setMinimumNumberShouldMatch(1);
        }
        return builder.build();
    }

    private Set<String> tokenize(String text) throws IOException {
        Set<String> tokens = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream(LuceneIndex.TEXT_FIELD, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String token = term.toString();
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
            stream.end();
        }
        return tokens;
    }

    public static Operator parseOperator(String value) {
        if (value == null || value.isBlank()) {
            return Operator.AND;
        }
        try {
            return Operator.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw HttpException.badRequest("operator must be AND or OR");
        }
    }
}
