package org.example.service;

import org.example.service.HybridSearchService.HybridSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 召回重排服务
 * 粗召回后按 RRF 分数阈值过滤，取 topK
 * 预留 LLM 精排接口
 */
@Service
public class ReRankService {

    private static final Logger logger = LoggerFactory.getLogger(ReRankService.class);

    /**
     * RRF 分数阈值，低于此值的结果将被过滤
     */
    @Value("${rag.rerank.rrf-threshold:0.02}")
    private double rrfThreshold;

    /**
     * 重排后返回的最大结果数量
     */
    @Value("${rag.rerank.top-k:5}")
    private int topK;

    /**
     * 重排入口
     * 先按 RRF 分数阈值过滤，再按 RRF 分数降序取 topK
     */
    public List<HybridSearchResult> rerank(List<HybridSearchResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        List<HybridSearchResult> filtered = new ArrayList<>();

        for (HybridSearchResult result : candidates) {
            double score = getEffectiveScore(result);
            if (score >= rrfThreshold) {
                filtered.add(result);
            }
        }

        if (filtered.isEmpty()) {
            logger.warn("RRF 阈值过滤后无结果，返回原始候选前 {} 条", topK);
            List<HybridSearchResult> fallback = new ArrayList<>(candidates);
            fallback.sort((a, b) -> Double.compare(getEffectiveScore(b), getEffectiveScore(a)));
            return fallback.subList(0, Math.min(topK, fallback.size()));
        }

        filtered.sort((a, b) -> Double.compare(getEffectiveScore(b), getEffectiveScore(a)));

        List<HybridSearchResult> topResults = filtered.subList(0, Math.min(topK, filtered.size()));

        logger.info("重排完成: {} 条候选 → {} 条过滤后 → {} 条最终结果",
                candidates.size(), filtered.size(), topResults.size());

        return topResults;
    }

    private double getEffectiveScore(HybridSearchResult result) {
        return result.getRrfScore() > 0 ? result.getRrfScore() : result.getDenseScore();
    }

    /**
     * LLM 精排（预留接口，BETA 版不启用）
     * TODO: 实现 LLM 精排逻辑
     *
     * 思路：将 query 和候选文档发送给 LLM，让其对相关性打分
     * 然后按分数重新排序
     */
    public List<HybridSearchResult> llmRerank(String query, List<HybridSearchResult> candidates) {
        // TODO: 实现 LLM 精排
        // 1. 构建 prompt: "请对以下文档与问题的相关性打分(1-5分)"
        // 2. 调用 LLM 获取 JSON 格式的评分结果
        // 3. 按 LLM 评分重新排序
        // 4. 返回重排后的结果
        logger.info("LLM 精排未启用，直接返回阈值过滤结果");
        return rerank(candidates);
    }
}
