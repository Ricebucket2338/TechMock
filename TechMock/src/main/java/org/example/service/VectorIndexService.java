package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 标记 sparse vector 是否可用（collection 是否包含 sparse_vector 字段）
     */
    private boolean sparseVectorAvailable = false;

    @PostConstruct
    public void init() {
        // 尝试通过插入一条测试数据检查 sparse_vector 字段是否存在
        try {
            Path testPath = Paths.get(uploadPath).normalize();
            File uploadDir = testPath.toFile();
            if (uploadDir.exists() && uploadDir.isDirectory()) {
                sparseVectorAvailable = checkSparseVectorField();
                logger.info("Sparse vector 字段可用性检查: {}", sparseVectorAvailable ? "可用" : "不可用");
            }
        } catch (Exception e) {
            logger.debug("Sparse vector 可用性检查失败: {}", e.getMessage());
            sparseVectorAvailable = false;
        }
    }

    /**
     * 检查 collection 是否包含 sparse_vector 字段
     */
    private boolean checkSparseVectorField() {
        try {
            R<io.milvus.grpc.DescribeCollectionResponse> descResponse = milvusClient.describeCollection(
                    io.milvus.param.collection.DescribeCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build());
            if (descResponse.getStatus() == 0 && descResponse.getData() != null) {
                var schema = descResponse.getData().getSchema();
                for (int i = 0; i < schema.getFieldsCount(); i++) {
                    if (MilvusConstants.SPARSE_VECTOR_FIELD.equals(schema.getFields(i).getName())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("检查 sparse_vector 字段失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 索引指定目录下的所有文件
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty())
                    ? directoryPath : uploadPath;
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();

            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            File[] files = directory.listFiles((dir, name) ->
                    name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}",
                    result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();

        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        deleteExistingData(path.toString());

        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        if (chunks.isEmpty()) {
            logger.warn("文档无有效分片，跳过索引: {}", filePath);
            return;
        }

        List<String> chunkContents = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            chunkContents.add(chunk.getContent());
        }

        List<List<Float>> vectors = embeddingService.generateEmbeddings(chunkContents);

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            List<Float> vector = vectors.get(i);

            try {
                SortedMap<Long, Float> sparseVector = null;
                if (sparseVectorAvailable) {
                    sparseVector = embeddingService.generateSparseVectorForMilvus(chunk.getContent());
                }

                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());

                insertToMilvus(chunk.getContent(), vector, sparseVector, metadata, chunk.getChunkIndex());

                logger.info("分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    /**
     * 删除文件的旧数据
     */
    private void deleteExistingData(String filePath) {
        try {
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);

            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build());

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();

        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");

        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }

        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);

        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }

        return metadata;
    }

    /**
     * 插入向量到 Milvus
     * 同时插入 dense vector 和 sparse vector（如果可用）
     */
    private void insertToMilvus(String content, List<Float> vector,
                                SortedMap<Long, Float> sparseVector,
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build());

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            String source = (String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));

            if (sparseVector != null && !sparseVector.isEmpty()) {
                fields.add(new InsertParam.Field(MilvusConstants.SPARSE_VECTOR_FIELD,
                        Collections.singletonList(sparseVector)));
            }

            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
