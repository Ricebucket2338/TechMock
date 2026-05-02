package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private String host = "localhost";
    private Integer port = 19530;
    private String username = "";
    private String password = "";
    private String database = "default";
    private Long timeout = 10000L;
    /**
     * 应用启动时是否重建 collection（删除旧数据）。
     * 生产环境建议设为 false，避免每次重启清空向量库。
     */
    private Boolean recreateOnStart = false;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public Long getTimeout() {
        return timeout;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public Boolean getRecreateOnStart() {
        return recreateOnStart;
    }

    public void setRecreateOnStart(Boolean recreateOnStart) {
        this.recreateOnStart = recreateOnStart;
    }

    public String getAddress() {
        return host + ":" + port;
    }
}
