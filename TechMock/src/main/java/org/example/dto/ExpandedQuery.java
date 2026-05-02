package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询扩展结果
 */
@Getter
@Setter
public class ExpandedQuery {

    /**
     * 原始问题
     */
    private String originalQuery;

    /**
     * 扩展后的查询列表（包含原始查询）
     */
    private List<String> expandedQueries = new ArrayList<>();

    public ExpandedQuery(String originalQuery) {
        this.originalQuery = originalQuery;
        this.expandedQueries.add(originalQuery);
    }

    public ExpandedQuery() {
    }

    public void addExpandedQuery(String query) {
        if (query != null && !query.trim().isEmpty()) {
            expandedQueries.add(query.trim());
        }
    }

    public List<String> getAllQueries() {
        return expandedQueries;
    }

    public int size() {
        return expandedQueries.size();
    }
}
