package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.AudioUploadConfig;
import org.example.dto.UploadInitResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioUploadService {

    private static final String UPLOAD_KEY_PREFIX = "upload:";
    private static final String HASH_KEY_PREFIX = "upload:hash:";
    private static final String CHUNKS_KEY_PREFIX = "upload:chunks:";
    private static final String MERGE_LOCK_PREFIX = "upload:merge:lock:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AudioUploadConfig audioUploadConfig;

    public UploadInitResponse initUpload(String fileName, Long fileSize) {
        validateFile(fileName, fileSize);

        String fileHash = computeFileHash(fileName, fileSize);
        String hashKey = HASH_KEY_PREFIX + fileHash;

        Object existingUploadId = redisTemplate.opsForValue().get(hashKey);
        if (existingUploadId != null) {
            String uploadId = existingUploadId.toString();
            return buildResponse(uploadId);
        }

        String uploadId = UUID.randomUUID().toString();
        Long chunkSize = calculateChunkSize(fileSize);
        int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);

        String infoKey = UPLOAD_KEY_PREFIX + uploadId;
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;

        redisTemplate.opsForHash().put(infoKey, "fileName", fileName);
        redisTemplate.opsForHash().put(infoKey, "fileSize", String.valueOf(fileSize));
        redisTemplate.opsForHash().put(infoKey, "chunkSize", String.valueOf(chunkSize));
        redisTemplate.opsForHash().put(infoKey, "totalChunks", String.valueOf(totalChunks));
        redisTemplate.opsForHash().put(infoKey, "status", "uploading");

        redisTemplate.opsForValue().set(hashKey, uploadId);
        redisTemplate.expire(infoKey, audioUploadConfig.getChunkTtlSeconds(), TimeUnit.SECONDS);
        redisTemplate.expire(hashKey, audioUploadConfig.getChunkTtlSeconds(), TimeUnit.SECONDS);
        redisTemplate.expire(chunksKey, audioUploadConfig.getChunkTtlSeconds(), TimeUnit.SECONDS);

        log.info("初始化上传 - uploadId: {}, fileName: {}, chunkSize: {}, totalChunks: {}",
                uploadId, fileName, chunkSize, totalChunks);

        return buildResponse(uploadId);
    }

    private void validateFile(String fileName, Long fileSize) {
        if (fileSize == null || fileSize <= 0) {
            throw new IllegalArgumentException("Invalid file size");
        }
        Long maxFileSize = audioUploadConfig.getMaxFileSize();
        if (maxFileSize != null && fileSize > maxFileSize) {
            throw new IllegalArgumentException(
                    "File size " + fileSize + " exceeds limit " + maxFileSize);
        }
        String ext = getFileExtension(fileName).replace(".", "").toLowerCase();
        String allowed = audioUploadConfig.getAllowedExtensions();
        if (allowed != null && !allowed.isEmpty()) {
            List<String> allowedList = List.of(allowed.split(","));
            if (!allowedList.contains(ext)) {
                throw new IllegalArgumentException(
                        "File extension ." + ext + " is not allowed");
            }
        }
    }

    /**
     * Upload a single chunk. Validates expected chunk size and prevents silent corruption.
     */
    public void uploadChunk(String uploadId, Integer chunkIndex, MultipartFile chunk) throws IOException {
        String infoKey = UPLOAD_KEY_PREFIX + uploadId;
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;

        Object status = redisTemplate.opsForHash().get(infoKey, "status");
        if (!"uploading".equals(status)) {
            throw new IllegalStateException("Upload is not in uploading state");
        }

        // Validate expected chunk size for all non-final chunks
        long expectedChunkSize = Long.parseLong(
                redisTemplate.opsForHash().get(infoKey, "chunkSize").toString());
        int totalChunks = Integer.parseInt(
                redisTemplate.opsForHash().get(infoKey, "totalChunks").toString());
        boolean isLastChunk = (chunkIndex == totalChunks - 1);

        if (!isLastChunk && chunk.getSize() != expectedChunkSize) {
            throw new IllegalArgumentException(
                    String.format("Chunk %d size %d does not match expected %d",
                            chunkIndex, chunk.getSize(), expectedChunkSize));
        }

        // Prevent duplicate upload of same chunk
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(chunksKey, chunkIndex))) {
            log.info("分片已存在，跳过 - uploadId: {}, chunkIndex: {}", uploadId, chunkIndex);
            return;
        }

        Path tempDir = Paths.get(audioUploadConfig.getBasePath(), "tmp", uploadId);
        Files.createDirectories(tempDir);

        Path chunkPath = tempDir.resolve(String.valueOf(chunkIndex));
        try (InputStream in = chunk.getInputStream()) {
            Files.copy(in, chunkPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        redisTemplate.opsForSet().add(chunksKey, chunkIndex);

        log.debug("上传分片成功 - uploadId: {}, chunkIndex: {}", uploadId, chunkIndex);
    }

    /**
     * Merge uploaded chunks into final audio file.
     * Returns merged file path and original filename.
     */
    public MergeResult mergeChunks(String uploadId) throws IOException {
        String infoKey = UPLOAD_KEY_PREFIX + uploadId;
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;
        String lockKey = MERGE_LOCK_PREFIX + uploadId;

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(acquired)) {
            throw new IllegalStateException("Merge already in progress for uploadId: " + uploadId);
        }

        try {
            // Validate Redis state exists
            Object status = redisTemplate.opsForHash().get(infoKey, "status");
            if (status == null || !"uploading".equals(status)) {
                throw new IllegalStateException(
                        "Upload session expired or already processed for uploadId: " + uploadId);
            }

            Object totalChunksObj = redisTemplate.opsForHash().get(infoKey, "totalChunks");
            if (totalChunksObj == null) {
                throw new IllegalStateException("Upload session metadata missing for uploadId: " + uploadId);
            }
            int totalChunks = Integer.parseInt(totalChunksObj.toString());

            Long uploadedCount = redisTemplate.opsForSet().size(chunksKey);
            if (uploadedCount == null || uploadedCount != totalChunks) {
                throw new IllegalStateException(
                        String.format("Incomplete upload: %d/%d chunks uploaded",
                                uploadedCount == null ? 0 : uploadedCount, totalChunks));
            }

            redisTemplate.opsForHash().put(infoKey, "status", "merging");

            String originalFileName = redisTemplate.opsForHash().get(infoKey, "fileName").toString();
            Long fileSize = Long.parseLong(
                    redisTemplate.opsForHash().get(infoKey, "fileSize").toString());

            Path dateDir = Paths.get(audioUploadConfig.getBasePath(), LocalDate.now().toString());
            Files.createDirectories(dateDir);

            Path mergedFile = dateDir.resolve(uploadId + getFileExtension(originalFileName));

            try (java.io.OutputStream out = Files.newOutputStream(mergedFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path chunkPath = Paths.get(audioUploadConfig.getBasePath(), "tmp", uploadId,
                            String.valueOf(i));
                    if (!Files.exists(chunkPath)) {
                        throw new IOException("Chunk " + i + " not found on disk");
                    }
                    Files.copy(chunkPath, out);
                    Files.delete(chunkPath);
                }
            }

            // Recursively delete tmp directory (handles non-empty state safely)
            Path tmpDir = Paths.get(audioUploadConfig.getBasePath(), "tmp", uploadId);
            deleteDirectoryRecursively(tmpDir);

            String hashKey = HASH_KEY_PREFIX + computeFileHash(originalFileName, fileSize);
            redisTemplate.delete(infoKey);
            redisTemplate.delete(chunksKey);
            redisTemplate.delete(hashKey);

            log.info("合并完成 - uploadId: {}, mergedFile: {}, originalFileName: {}",
                    uploadId, mergedFile, originalFileName);
            return new MergeResult(mergedFile, originalFileName);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * Result of merge operation containing both merged file path and original filename.
     */
    public static class MergeResult {
        private final Path mergedFile;
        private final String originalFileName;

        public MergeResult(Path mergedFile, String originalFileName) {
            this.mergedFile = mergedFile;
            this.originalFileName = originalFileName;
        }

        public Path getMergedFile() {
            return mergedFile;
        }

        public String getOriginalFileName() {
            return originalFileName;
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
        }
    }

    private UploadInitResponse buildResponse(String uploadId) {
        String infoKey = UPLOAD_KEY_PREFIX + uploadId;
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;

        long chunkSize = Long.parseLong(
                redisTemplate.opsForHash().get(infoKey, "chunkSize").toString());
        int totalChunks = Integer.parseInt(
                redisTemplate.opsForHash().get(infoKey, "totalChunks").toString());

        Set<Object> uploadedSet = redisTemplate.opsForSet().members(chunksKey);
        List<Integer> uploadedChunks = uploadedSet == null ? new ArrayList<>() :
                uploadedSet.stream()
                        .map(o -> Integer.parseInt(o.toString()))
                        .sorted()
                        .collect(Collectors.toList());

        UploadInitResponse response = new UploadInitResponse();
        response.setUploadId(uploadId);
        response.setChunkSize(chunkSize);
        response.setTotalChunks(totalChunks);
        response.setUploadedChunks(uploadedChunks);
        return response;
    }

    private Long calculateChunkSize(Long fileSize) {
        if (fileSize < 10 * 1024 * 1024) {
            return 2 * 1024 * 1024L;
        } else if (fileSize < 100 * 1024 * 1024) {
            return 5 * 1024 * 1024L;
        } else if (fileSize < 500 * 1024 * 1024) {
            return 10 * 1024 * 1024L;
        } else {
            return 20 * 1024 * 1024L;
        }
    }

    private String computeFileHash(String fileName, Long fileSize) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((fileName + fileSize).getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : "";
    }
}
