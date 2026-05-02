package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UploadInitRequest {
    private String fileName;
    private Long fileSize;
}
