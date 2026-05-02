package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "audio.upload")
public class AudioUploadConfig {

    private String basePath;
    private String allowedExtensions;
    private Long maxFileSize;
    private Long chunkTtlSeconds;
}
