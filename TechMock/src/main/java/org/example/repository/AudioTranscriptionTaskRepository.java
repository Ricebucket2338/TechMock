package org.example.repository;

import org.example.entity.AudioTranscriptionTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AudioTranscriptionTaskRepository extends JpaRepository<AudioTranscriptionTask, String> {

    List<AudioTranscriptionTask> findByOrderByCreatedAtDesc();
}
