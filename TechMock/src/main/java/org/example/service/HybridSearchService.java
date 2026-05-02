package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.AnnSearchParam;
import io.milvus.param.dml.HybridSearchParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.ranker.RRFRanker;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 混合检索服务
 * 使用 Milvus 原生 HybridSearch + RRFRanker 实现 dense + sparse 混合检索
 *
 * 批量优化：所有查询的 dense embedding 一次性批量生成，减少 API 调用次数。
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Value("${rag.hybrid.dense-top-k:20}")
    private int denseTopK;

    @Value("${rag.hybrid.sparse-top-k:20}")
    private int sparseTopK;

    /**
     * 混合检索入口
     * 对所有查询批量生成 embedding，然后分别做 dense + sparse 检索，合并去重后返回
     */
    public List<HybridSearchResult> hybridSearch(List<String> queries, int topK) {
        if (queries == null || queries.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量生成 dense embeddings（一次 API 调用）
        Map<String, List<Float>> denseVectors = batchGenerateDenseVectors(queries);

        // 批量生成 sparse vectors（本地计算，无网络开销）
        Map<String, SortedMap<Long, Float>> sparseVectors = batchGenerateSparseVectors(queries);

        Map<String, HybridSearchResult> allResults = new LinkedHashMap<>();

        for (String query : queries) {
            try {
                List<Float> denseVector = denseVectors.get(query);
                if (denseVector == null) {
                    logger.warn("查询 '{}' 的 dense embedding 生成失败，跳过", query);
                    continue;
                }

                Map<String, HybridSearchResult> queryResults = searchSingleQuery(query, denseVector, sparseVectors.get(query));
                for (Map.Entry<String, HybridSearchResult> entry : queryResults.entrySet()) {
                    String docId = entry.getKey();
                    HybridSearchResult result = entry.getValue();
                    if (!allResults.containsKey(docId)) {
                        allResults.put(docId, result);
                    } else {
                        // 多查询命中同一文档：累加 RRF 分数
                        HybridSearchResult existing = allResults.get(docId);
                        existing.setRrfScore(existing.getRrfScore() + result.getRrfScore());
                    }
                }
            } catch (Exception e) {
                logger.warn("查询 '{}' 混合检索失败: {}, 回退到 dense 检索", query, e.getMessage());
                try {
                    List<HybridSearchResult> denseResults = searchDenseOnly(query);
                    for (HybridSearchResult r : denseResults) {
                        if (!allResults.containsKey(r.getId())) {
                            allResults.put(r.getId(), r);
                        }
                    }
                } catch (Exception ex) {
                    logger.error("dense 检索也失败: {}", ex.getMessage());
                }
            }
        }

        List<HybridSearchResult> sortedResults = new ArrayList<>(allResults.values());
        sortedResults.sort((a, b) -> Double.compare(b.getRrfScore() > 0 ? b.getRrfScore() : b.getDenseScore(),
                a.getRrfScore() > 0 ? a.getRrfScore() : a.getDenseScore()));

        int limit = Math.min(topK, sortedResults.size());
        List<HybridSearchResult> topResults = sortedResults.subList(0, limit);

        logger.info("混合检索完成: {} 个查询, {} 个候选, 返回 top {},检索内容 {}",
                queries.size(), allResults.size(), topResults.size(),allResults);

        return topResults;
    }

    /**
     * 批量生成 dense embeddings（一次 API 调用）
     */
    private Map<String, List<Float>> batchGenerateDenseVectors(List<String> queries) {
        Map<String, List<Float>> vectors = new LinkedHashMap<>();
        try {
            List<List<Float>> embeddings = embeddingService.generateEmbeddings(queries);
            for (int i = 0; i < queries.size(); i++) {
                vectors.put(queries.get(i), embeddings.get(i));
            }
        } catch (Exception e) {
            logger.error("批量生成 dense embedding 失败: {}", e.getMessage());
            // 失败时降级为逐个生成
            for (String query : queries) {
                try {
                    vectors.put(query, embeddingService.generateQueryVector(query));
                } catch (Exception ex) {
                    logger.error("查询 '{}' 的 dense embedding 生成失败: {}", query, ex.getMessage());
                }
            }
        }
        return vectors;
    }

    /**
     * 批量生成 sparse vectors（本地计算）
     */
    private Map<String, SortedMap<Long, Float>> batchGenerateSparseVectors(List<String> queries) {
        Map<String, SortedMap<Long, Float>> vectors = new LinkedHashMap<>();
        for (String query : queries) {
            try {
                vectors.put(query, embeddingService.generateSparseVectorForMilvus(query));
            } catch (Exception e) {
                logger.warn("查询 '{}' 的 sparse vector 生成失败: {}", query, e.getMessage());
            }
        }
        return vectors;
    }

    /**
     * 单次查询的混合检索（dense + sparse，Milvus 原生 RRF 融合）
     */
    private Map<String, HybridSearchResult> searchSingleQuery(String query,
                                                              List<Float> denseVector,
                                                              SortedMap<Long, Float> sparseVector) {
        // dense AnnSearchParam
        AnnSearchParam denseParam = AnnSearchParam.newBuilder()
                .withVectorFieldName("vector")
                .withFloatVectors(Collections.singletonList(denseVector))
                .withLimit((long) denseTopK)
                .withMetricType(MetricType.L2)
                .withParams("{\"nprobe\":16}")
                .build();

        // sparse AnnSearchParam（sparse 索引只支持 IP 度量）
        AnnSearchParam sparseParam = AnnSearchParam.newBuilder()
                .withVectorFieldName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withSparseFloatVectors(sparseVector != null ? Collections.singletonList(sparseVector) : Collections.emptyList())
                .withLimit((long) sparseTopK)
                .withMetricType(MetricType.IP)
                .withParams("{\"drop_ratio_search\":0.05}")
                .build();

        // HybridSearchParam with RRF ranker
        HybridSearchParam hybridParam = HybridSearchParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .addSearchRequest(denseParam)
                .addSearchRequest(sparseParam)
                .withRanker(RRFRanker.newBuilder().withK(60).build())
                .withLimit((long) denseTopK)
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .build();

        // 执行混合检索
        R<SearchResults> response = milvusClient.hybridSearch(hybridParam);
        if (response.getStatus() != 0) {
            logger.warn("混合检索失败: {}, 回退到 dense 检索", response.getMessage());
            return searchDenseOnlyResultsWithVector(query, denseVector);
        }

        return parseResults(response.getData());
    }

    /**
     * 仅 dense 向量检索（回退方案）
     */
    private List<HybridSearchResult> searchDenseOnly(String query) {
        try {
            List<Float> denseVector = embeddingService.generateQueryVector(query);
            return searchDenseOnlyWithVector(denseVector);
        } catch (Exception e) {
            logger.error("dense 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 使用预生成的 dense 向量做检索
     */
    private Map<String, HybridSearchResult> searchDenseOnlyResultsWithVector(String query, List<Float> denseVector) {
        Map<String, HybridSearchResult> results = new LinkedHashMap<>();
        try {
            List<HybridSearchResult> denseResults = searchDenseOnlyWithVector(denseVector);
            for (HybridSearchResult r : denseResults) {
                results.put(r.getId(), r);
            }
        } catch (Exception e) {
            logger.error("dense 回退检索也失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 使用预生成的 dense 向量执行检索
     */
    private List<HybridSearchResult> searchDenseOnlyWithVector(List<Float> denseVector) {
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withVectorFieldName("vector")
                .withFloatVectors(Collections.singletonList(denseVector))
                .withLimit((long) denseTopK)
                .withMetricType(MetricType.L2)
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .withParams("{\"nprobe\":16}")
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() == 0) {
            return new ArrayList<>(parseResults(response.getData()).values());
        }
        return Collections.emptyList();
    }

    /**
     * 解析 Milvus 搜索结果
     * 混合检索(RRFRanker)返回的 score 即为 RRF 融合分数
     */
    private Map<String, HybridSearchResult> parseResults(SearchResults results) {
        Map<String, HybridSearchResult> parsed = new LinkedHashMap<>();

        try {
            SearchResultsWrapper wrapper = new SearchResultsWrapper(results.getResults());

            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                HybridSearchResult result = new HybridSearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setRrfScore(wrapper.getIDScore(0).get(i).getScore());

                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }

                parsed.put(result.getId(), result);
            }
        } catch (Exception e) {
            logger.error("解析搜索结果失败: {}", e.getMessage());
        }

        return parsed;
    }

    /**
     * 混合检索结果
     */
    @Getter
    @Setter
    public static class HybridSearchResult {
        private String id;
        private String content;
        private double denseScore;
        private double sparseScore;
        private double rrfScore;
        private String metadata;

        /**
         * 兼容旧接口：返回综合分数
         */
        public double getScore() {
            return rrfScore > 0 ? rrfScore : denseScore;
        }

        public void setScore(double score) {
            this.denseScore = score;
        }
    }
}
