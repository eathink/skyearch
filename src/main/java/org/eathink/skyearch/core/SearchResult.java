package org.eathink.skyearch.core;

import java.util.List;

/** 搜索结果: 只包含 Document ID。 */
public record SearchResult(List<String> ids, long total) {
}
