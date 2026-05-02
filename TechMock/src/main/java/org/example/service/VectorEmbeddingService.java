package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

/**
 * 向量嵌入服务
 * 使用阿里云 DashScope Text Embedding API
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    private TextEmbedding textEmbedding;

    /**
     * Jieba 中文分词器
     */
    private JiebaSegmenter jiebaSegmenter;

    @PostConstruct
    public void init() {
        // 验证 API Key
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            logger.error("API Key 未正确配置");
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY 或在 application.yml 中配置正确的 API Key");
        }

        Constants.apiKey = apiKey;
        logger.info("API Key 已加载");

        textEmbedding = new TextEmbedding();
        jiebaSegmenter = new JiebaSegmenter();

        logger.info("阿里云 DashScope Embedding 服务初始化完成，模型: {}", model);
    }

    /**
     * 生成向量嵌入
     * 调用阿里云 DashScope Text Embedding API
     * 
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                logger.warn("内容为空，无法生成向量");
                throw new IllegalArgumentException("内容不能为空");
            }

            logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());
            
            // 确保 API Key 已设置（防止被其他地方覆盖）
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                Constants.apiKey = apiKey;
            }

            // 构建请求参数
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(Collections.singletonList(content))
                    .build();

            // 调用 API
            TextEmbeddingResult result = textEmbedding.call(param);

            // 检查结果
            List<Float> floatEmbedding = getFloats(result);

            logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}", 
                content.length(), floatEmbedding.size());

            return floatEmbedding;

        } catch (NoApiKeyException e) {
            logger.error("API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static List<Float> getFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope API 返回空结果");
        }

        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> embeddings = output.getEmbeddings();

        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope API 返回空向量列表");
        }

        // 获取第一个文本的向量
        List<Double> embeddingDoubles = embeddings.get(0).getEmbedding();

        // 转换为 List<Float>
        List<Float> floatEmbedding = new ArrayList<>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            floatEmbedding.add(value.floatValue());
        }
        return floatEmbedding;
    }

    /**
     * 批量生成向量嵌入
     * 
     * @param contents 文本内容列表
     * @return 向量嵌入列表
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                logger.warn("内容列表为空，无法生成向量");
                return Collections.emptyList();
            }

            logger.info("开始批量生成向量嵌入, 数量: {}", contents.size());

            // 确保 API Key 已设置
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                Constants.apiKey = apiKey;
            }

            // 构建请求参数 - 批量输入
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(contents)
                    .build();

            // 调用 API
            TextEmbeddingResult result = textEmbedding.call(param);

            // 检查结果
            if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
                throw new RuntimeException("批量 DashScope API 返回空结果");
            }

            List<TextEmbeddingResultItem> embeddingItems = result.getOutput().getEmbeddings();
            
            if (embeddingItems.isEmpty()) {
                throw new RuntimeException("批量 DashScope API 返回空向量列表");
            }

            // 转换结果
            List<List<Float>> embeddings = new ArrayList<>();
            for (TextEmbeddingResultItem item : embeddingItems) {
                List<Double> embeddingDoubles = item.getEmbedding();
                List<Float> embedding = new ArrayList<>(embeddingDoubles.size());
                for (Double value : embeddingDoubles) {
                    embedding.add(value.floatValue());
                }
                embeddings.add(embedding);
            }

            logger.info("成功批量生成向量嵌入, 数量: {}, 维度: {}", 
                embeddings.size(), 
                embeddings.isEmpty() ? 0 : embeddings.get(0).size());

            return embeddings;

        } catch (NoApiKeyException e) {
            logger.error("批量调用时 API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     * 
     * @param query 查询文本
     * @return 向量嵌入
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    /**
     * 生成稀疏向量（Milvus 适配版）
     * 返回 SortedMap<Long, Float> 以匹配 Milvus SDK withSparseFloatVectors() 所需的类型
     *
     * @param text 输入文本
     * @return SortedMap<Long, Float>，可直接传入 withSparseFloatVectors()
     */
    public SortedMap<Long, Float> generateSparseVectorForMilvus(String text) {
        Map<Integer, Float> raw = generateSparseVector(text);
        SortedMap<Long, Float> result = new TreeMap<>();
        for (Map.Entry<Integer, Float> entry : raw.entrySet()) {
            result.put(entry.getKey().longValue(), entry.getValue());
        }
        return result;
    }

    /**
     * 生成稀疏向量（用于 Milvus sparse vector 检索）
     * 使用 Jieba 分词 → 过滤停用词 → 词频统计 → Map(termId, tfScore)
     *
     * @param text 输入文本
     * @return 稀疏向量，key 为 term 的 hash ID，value 为 TF 分数
     */
    public Map<Integer, Float> generateSparseVector(String text) {
        if (text == null || text.trim().isEmpty()) {
            logger.warn("文本为空，无法生成稀疏向量");
            return Collections.emptyMap();
        }

        if (jiebaSegmenter == null) {
            logger.warn("Jieba 分词器未初始化，回退到按字分割");
            return generateSparseVectorByChar(text);
        }

        try {
            List<SegToken> tokens = jiebaSegmenter.process(text, JiebaSegmenter.SegMode.SEARCH);

            Map<String, Integer> termFreq = new HashMap<>();
            for (SegToken token : tokens) {
                String word = token.word.trim();
                if (isStopWord(word)) {
                    continue;
                }
                if (word.length() < 2 && !isChineseChar(word)) {
                    continue;
                }
                termFreq.merge(word, 1, Integer::sum);
            }

            Map<Integer, Float> sparseVector = new HashMap<>();
            int maxTerms = Math.min(termFreq.size(), 200);
            int count = 0;
            for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
                if (count >= maxTerms) {
                    break;
                }
                int termId = Math.abs(entry.getKey().hashCode());
                float tfScore = entry.getValue().floatValue();
                sparseVector.put(termId, tfScore);
                count++;
            }

            logger.debug("稀疏向量生成: 原文长度={}, 分词数={}, 过滤后={}",
                    text.length(), tokens.size(), sparseVector.size());

            return sparseVector;

        } catch (Exception e) {
            logger.error("稀疏向量生成失败，回退到按字分割: {}", e.getMessage());
            return generateSparseVectorByChar(text);
        }
    }

    private Map<Integer, Float> generateSparseVectorByChar(String text) {
        Map<Integer, Float> sparseVector = new HashMap<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= ' ' || isStopWord(String.valueOf(c))) {
                continue;
            }
            int termId = Math.abs(String.valueOf(c).hashCode());
            sparseVector.merge(termId, 1.0f, Float::sum);
        }
        return sparseVector;
    }

    private boolean isStopWord(String word) {
        if (word == null || word.isEmpty()) {
            return true;
        }
        return switch (word) {
            case "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
                 "都", "一", "一个", "上", "也", "很", "到", "说", "要",
                 "去", "你", "会", "着", "没有", "看", "好", "这", "那",
                 "吗", "呢", "啊", "吧", "哦", "呀", "什么", "怎么",
                 "这个", "那个", "哪里", "为什么", "谁" -> true;
            default -> false;
        };
    }

    private boolean isChineseChar(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        return c >= '一' && c <= '鿿';
    }

    /**
     * 计算两个向量的余弦相似度
     * 
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 余弦相似度 [-1, 1]
     */
    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
