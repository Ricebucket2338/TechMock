package org.example.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "audio_transcription_task")
@Getter
@Setter
public class AudioTranscriptionTask {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "upload_id", length = 36)
    private String uploadId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private InterviewSession session;

    @Column(name = "file_path", length = 512)
    private String filePath;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(nullable = false, length = 20)
    private String status = "queued";

    @Column(nullable = false)
    private Integer progress = 0;

    @Lob
    @Column(name = "transcript_text", columnDefinition = "LONGTEXT")
    private String transcriptText;

    @Lob
    @Column(name = "speaker_segments", columnDefinition = "LONGTEXT")
    private String speakerSegments;

    @Lob
    @Column(name = "review_report", columnDefinition = "LONGTEXT")
    private String reviewReport;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
