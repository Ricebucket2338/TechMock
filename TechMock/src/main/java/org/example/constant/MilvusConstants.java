package org.example.constant;

public class MilvusConstants {

    /**
     * Milvus 数据库名称
     */
    public static final String MILVUS_DB_NAME = "default";

    /**
     * Milvus 集合名称
     */
    public static final String MILVUS_COLLECTION_NAME = "biz";

    /**
     * Dense 向量维度（DashScope embedding 模型的维度）
     */
    public static final int VECTOR_DIM = 1024;

    /**
     * Sparse 向量字段名
     */
    public static final String SPARSE_VECTOR_FIELD = "sparse_vector";

    /**
     * 向量度量类型：与 collection 创建时的索引一致
     * 注意：实际度量类型在 SearchParam/HybridSearchParam 中直接指定
     */
    public static final io.milvus.param.MetricType METRIC_TYPE = io.milvus.param.MetricType.L2;

    /**
     * ID字段最大长度
     */
    public static final int ID_MAX_LENGTH = 256;

    /**
     * Content字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;

    /**
     * 默认分片数
     */
    public static final int DEFAULT_SHARD_NUMBER = 2;

    /**
     * Dense 向量索引类型
     */
    public static final io.milvus.param.IndexType DENSE_INDEX_TYPE = io.milvus.param.IndexType.IVF_FLAT;

    /**
     * Sparse 向量索引类型
     */
    public static final io.milvus.param.IndexType SPARSE_INDEX_TYPE = io.milvus.param.IndexType.SPARSE_INVERTED_INDEX;

    private MilvusConstants() {
        // 工具类，禁止实例化
    }
}
