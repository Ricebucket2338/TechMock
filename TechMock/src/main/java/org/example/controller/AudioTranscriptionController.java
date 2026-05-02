package org.example.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.*;
import org.example.entity.AudioTranscriptionTask;
import org.example.mq.TranscriptionProducer;
import org.example.repository.AudioTranscriptionTaskRepository;
import org.example.service.AudioUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
public class AudioTranscriptionController {

    private final AudioUploadService audioUploadService;
    private final AudioTranscriptionTaskRepository taskRepository;
    private final TranscriptionProducer transcriptionProducer;

    @PostMapping("/upload/init")
    public ResponseEntity<UploadInitResponse> initUpload(@RequestBody UploadInitRequest request) {
        log.info("初始化上传 - fileName: {}, fileSize: {}", request.getFileName(), request.getFileSize());
        UploadInitResponse response = audioUploadService.initUpload(request.getFileName(), request.getFileSize());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/upload/chunk", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam Integer chunkIndex,
            @RequestParam("chunk") MultipartFile chunk) throws IOException {
        audioUploadService.uploadChunk(uploadId, chunkIndex, chunk);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/upload/merge")
    public ResponseEntity<Map<String, String>> mergeChunks(@RequestBody UploadMergeRequest request) throws IOException {
        AudioUploadService.MergeResult result = audioUploadService.mergeChunks(request.getUploadId());

        AudioTranscriptionTask task = new AudioTranscriptionTask();
        task.setId(UUID.randomUUID().toString());
        task.setUploadId(request.getUploadId());
        task.setFilePath(result.getMergedFile().toString());
        task.setFileName(result.getOriginalFileName());
        task.setStatus("queued");
        task.setProgress(0);
        taskRepository.save(task);

        try {
            transcriptionProducer.sendTranscribe(task.getId());
        } catch (Exception e) {
            log.error("发送转写消息失败，任务将在 MQ 恢复后自动消费 - taskId: {}, error: {}",
                    task.getId(), e.getMessage());
            // Task remains in "queued" status, will be picked up when MQ is available
        }

        log.info("创建转写任务 - taskId: {}, fileName: {}", task.getId(), task.getFileName());
        return ResponseEntity.ok(Map.of("taskId", task.getId(), "fileName", task.getFileName()));
    }

    @GetMapping("/task/{taskId}/status")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        AudioTranscriptionTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        TaskStatusResponse response = new TaskStatusResponse();
        response.setTaskId(task.getId());
        response.setFileName(task.getFileName());
        response.setStatus(task.getStatus());
        response.setProgress(task.getProgress());
        response.setErrorMessage(task.getErrorMessage());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/task/{taskId}/transcript")
    public ResponseEntity<TranscriptResponse> getTranscript(@PathVariable String taskId) {
        AudioTranscriptionTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        TranscriptResponse response = new TranscriptResponse();
        response.setTranscript(task.getTranscriptText());
        response.setSpeakerSegments(task.getSpeakerSegments());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/task/{taskId}/report")
    public ResponseEntity<Map<String, String>> getReport(@PathVariable String taskId) {
        AudioTranscriptionTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return ResponseEntity.ok(Map.of("report", task.getReviewReport() != null ? task.getReviewReport() : ""));
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<TaskListItem>> listTasks() {
        List<TaskListItem> result = taskRepository.findByOrderByCreatedAtDesc().stream()
                .map(this::toListItem)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/task/{taskId}")
    public ResponseEntity<Map<String, String>> deleteTask(@PathVariable String taskId) {
        if (!taskRepository.existsById(taskId)) {
            return ResponseEntity.notFound().build();
        }
        taskRepository.deleteById(taskId);
        return ResponseEntity.ok(Map.of("message", "Task deleted"));
    }

    private TaskListItem toListItem(AudioTranscriptionTask task) {
        TaskListItem item = new TaskListItem();
        item.setTaskId(task.getId());
        item.setFileName(task.getFileName());
        item.setStatus(task.getStatus());
        item.setProgress(task.getProgress());
        item.setReviewReport(task.getReviewReport());
        item.setCreatedAt(task.getCreatedAt());
        item.setUpdatedAt(task.getUpdatedAt());
        return item;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        log.warn("Conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIoException(IOException e) {
        log.error("IO error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "文件处理失败，请重试"));
    }
}
