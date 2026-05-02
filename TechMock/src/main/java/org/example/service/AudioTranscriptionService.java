package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.example.entity.AudioTranscriptionTask;
import org.example.mq.TranscriptionProducer;
import org.example.repository.AudioTranscriptionTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Audio transcription service using SiliconFlow API.
 *
 * Uses OkHttp multipart upload with streaming to avoid loading
 * entire file into memory.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioTranscriptionService {

    private final AudioTranscriptionTaskRepository taskRepository;
    private final TranscriptionProducer transcriptionProducer;
    private final Gson gson = new Gson();

    @Value("${siliconflow.api-key}")
    private String apiKey;

    @Value("${siliconflow.audio.model:TeleAI/TeleSpeechASR}")
    private String model;

    @Value("${siliconflow.audio.max-file-size-mb:500}")
    private int maxFileSizeMb;

    private static final String API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final long EXPECTED_CHUNK_SLOP_BYTES = 256;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    @PreDestroy
    public void shutdown() throws IOException {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        if (client.cache() != null) {
            client.cache().close();
        }
        log.info("AudioTranscriptionService OkHttp resources released");
    }

    /**
     * Transcribe audio file using SiliconFlow API.
     */
    public void transcribe(AudioTranscriptionTask task) {
        String localPath = task.getFilePath();
        File audioFile = new File(localPath);

        if (!audioFile.exists()) {
            failTask(task, "Audio file not found: " + localPath);
            return;
        }

        long fileSizeMb = audioFile.length() / (1024 * 1024);
        if (fileSizeMb > maxFileSizeMb) {
            failTask(task, "File size " + fileSizeMb + "MB exceeds limit " + maxFileSizeMb + "MB");
            return;
        }

        task.setStatus("transcribing");
        task.setProgress(10);
        taskRepository.save(task);

        log.info("开始转写 - taskId: {}, file: {}, size: {}MB",
                task.getId(), task.getFileName(), fileSizeMb);

        try {
            String transcript = transcribeWithRetry(audioFile);
            task.setTranscriptText(transcript);
            task.setSpeakerSegments(buildSegmentJson("unknown", transcript));
            task.setStatus("transcribed");
            task.setProgress(60);
            taskRepository.save(task);

            transcriptionProducer.sendReview(task.getId());
            log.info("转写完成 - taskId: {}, textLength: {}", task.getId(), transcript.length());
        } catch (IOException e) {
            failTask(task, "Transcription IO error: " + e.getMessage());
        }
    }

    private void failTask(AudioTranscriptionTask task, String message) {
        task.setStatus("failed");
        task.setErrorMessage(message);
        taskRepository.save(task);
        log.error("转写失败 - taskId: {}, error: {}", task.getId(), message);
    }

    /**
     * Upload audio file to SiliconFlow with retry on 5xx errors.
     */
    private String transcribeWithRetry(File audioFile) throws IOException {
        String lastError = "";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("上传转写中 - attempt: {}/{}", attempt, MAX_RETRIES);

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", audioFile.getName(),
                                RequestBody.create(audioFile, MediaType.parse("application/octet-stream")))
                        .addFormDataPart("model", model)
                        .build();

                Request request = new Request.Builder()
                        .url(API_URL)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                        if (json.has("text") && !json.get("text").getAsString().isEmpty()) {
                            return json.get("text").getAsString();
                        }
                        throw new IOException("API response missing 'text' field");
                    }

                    String errBody = response.body() != null ? response.body().string() : "";
                    lastError = String.format("HTTP %d: %s", response.code(), errBody);
                    log.warn("转写请求失败 ({}/{}): {}", attempt, MAX_RETRIES, lastError);

                    if (response.code() >= 500) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    throw new IOException("Client error: " + lastError);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Transcription interrupted", e);
            } catch (IOException e) {
                lastError = e.getMessage();
                log.warn("转写异常 ({}/{}): {}", attempt, MAX_RETRIES, lastError);
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry interrupted", ie);
                    }
                }
            }
        }

        throw new IOException("All " + MAX_RETRIES + " attempts failed. Last error: " + lastError);
    }

    /**
     * Build speakerSegments JSON array with proper escaping via Gson.
     */
    private String buildSegmentJson(String speaker, String text) {
        Map<String, Object> segment = Map.of(
                "speaker", speaker,
                "text", text,
                "start_time", 0,
                "end_time", 0
        );
        return gson.toJson(new Object[]{segment});
    }
}
