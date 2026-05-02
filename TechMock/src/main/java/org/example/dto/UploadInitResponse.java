package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UploadInitResponse {
    private String uploadId;
    private Long chunkSize;
    private Integer totalChunks;
    private List<Integer> uploadedChunks;
}
