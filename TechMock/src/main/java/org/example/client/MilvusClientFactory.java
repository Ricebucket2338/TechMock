package org.example.client;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 客户端工厂类
 * 负责创建和初始化 Milvus 客户端连接
 */
@Component
public class MilvusClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);

    @Autowired
    private MilvusProperties milvusProperties;

    /**
     * 创建并初始化 Milvus 客户端
     * 
     * 简化版本：直接连接并创建 collection
     * 
     * @return MilvusServiceClient 实例
     * @throws RuntimeException 如果连接或初始化失败
     */
    public MilvusServiceClient createClient() {
        MilvusServiceClient client = null;

        try {
            // 1. 连接到 Milvus
            logger.info("正在连接到 Milvus: {}:{}", milvusProperties.getHost(), milvusProperties.getPort());
            client = connectToMilvus();
            logger.info("成功连接到 Milvus");

            // 2. 根据 recreateOnStart 配置决定是否重建 collection
            boolean recreateOnStart = milvusProperties.getRecreateOnStart();
            if (recreateOnStart) {
                // 开发模式：每次启动都重建，确保 schema 和索引完整
                if (collectionExists(client, MilvusConstants.MILVUS_COLLECTION_NAME)) {
                    logger.info("collection '{}' 已存在，正在删除旧数据（recreateOnStart=true）...",
                            MilvusConstants.MILVUS_COLLECTION_NAME);
                    dropCollection(client, MilvusConstants.MILVUS_COLLECTION_NAME);
                }
                createBizCollection(client);
                createIndexes(client);
                logger.info("成功重建 collection '{}' 及完整索引", MilvusConstants.MILVUS_COLLECTION_NAME);
            } else {
                // 生产模式：保留已有数据，仅在 collection 不存在时创建
                if (collectionExists(client, MilvusConstants.MILVUS_COLLECTION_NAME)) {
                    logger.info("collection '{}' 已存在，保留已有数据（recreateOnStart=false）",
                            MilvusConstants.MILVUS_COLLECTION_NAME);
                } else {
                    createBizCollection(client);
                    createIndexes(client);
                    logger.info("成功创建 collection '{}' 及完整索引", MilvusConstants.MILVUS_COLLECTION_NAME);
                }
            }

            // 3. 加载 collection 到内存（Milvus 必须 load 后才能搜索）
            loadCollection(client);

            return client;

        } catch (Exception e) {
            logger.error("创建 Milvus 客户端失败", e);
            if (client != null) {
                client.close();
            }
            throw new RuntimeException("创建 Milvus 客户端失败: " + e.getMessage(), e);
        }
    }

    /**
     * 连接到 Milvus
     */
    private MilvusServiceClient connectToMilvus() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(milvusProperties.getHost())
                .withPort(milvusProperties.getPort())
                .withConnectTimeout(milvusProperties.getTimeout(), TimeUnit.MILLISECONDS);

        // 如果配置了用户名和密码
        if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
            builder.withAuthorization(milvusProperties.getUsername(), milvusProperties.getPassword());
        }

        return new MilvusServiceClient(builder.build());
    }

    /**
     * 检查 collection 是否存在
     */
    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        if (response.getStatus() != 0) {
            throw new RuntimeException("检查 collection 失败: " + response.getMessage());
        }

        return response.getData();
    }

    /**
     * 删除 collection
     */
    private void dropCollection(MilvusServiceClient client, String collectionName) {
        R<RpcStatus> response = client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        if (response.getStatus() != 0) {
            throw new RuntimeException("删除 collection 失败: " + response.getMessage());
        }
        logger.info("已删除 collection '{}'", collectionName);
    }

    /**
     * 加载 collection 到内存（Milvus 必须 load 后才能搜索）
     */
    private void loadCollection(MilvusServiceClient client) {
        R<RpcStatus> response = client.loadCollection(io.milvus.param.collection.LoadCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .build());
        if (response.getStatus() != 0) {
            throw new RuntimeException("加载 collection 到内存失败: " + response.getMessage());
        }
        logger.info("成功加载 collection '{}' 到内存", MilvusConstants.MILVUS_COLLECTION_NAME);
    }

    /**
     * 创建 biz collection
     * schema: id(VARCHAR), content(VARCHAR), vector(FLOAT_VECTOR),
     *         sparse_vector(SPARSE_FLOAT_VECTOR), metadata(JSON)
     */
    private void createBizCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        FieldType sparseVectorField = FieldType.newBuilder()
                .withName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withDataType(DataType.SparseFloatVector)
                .build();

        // 创建 collection schema
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(sparseVectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .build();

        // 创建 collection
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDescription("Business knowledge collection with dense+sparse vectors")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 collection 创建索引
     * dense 向量使用 IVF_FLAT + IP（内积）
     * sparse 向量使用 SPARSE_INVERTED_INDEX + IP
     */
    private void createIndexes(MilvusServiceClient client) {
        // 为 dense vector 字段创建索引
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> vectorResponse = client.createIndex(vectorIndexParam);
        if (vectorResponse.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + vectorResponse.getMessage());
        }
        logger.info("成功为 dense vector 字段创建索引");

        // 为 sparse vector 字段创建索引（sparse 索引只支持 IP 度量类型）
        CreateIndexParam sparseIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withIndexType(IndexType.SPARSE_INVERTED_INDEX)
                .withMetricType(MetricType.IP)
                .withExtraParam("{\"drop_ratio_build\":0.05}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> sparseResponse = client.createIndex(sparseIndexParam);
        if (sparseResponse.getStatus() != 0) {
            throw new RuntimeException("创建 sparse_vector 索引失败: " + sparseResponse.getMessage());
        }
        logger.info("成功为 sparse_vector 字段创建索引");
    }
}
