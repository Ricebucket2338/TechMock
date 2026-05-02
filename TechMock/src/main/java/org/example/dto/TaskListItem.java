package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TaskListItem {
    private String taskId;
    private String fileName;
    private String status;
    private Integer progress;
    private String reviewReport;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
