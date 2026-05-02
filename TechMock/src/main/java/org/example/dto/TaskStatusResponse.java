package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskStatusResponse {
    private String taskId;
    private String fileName;
    private String status;
    private Integer progress;
    private String errorMessage;
}
