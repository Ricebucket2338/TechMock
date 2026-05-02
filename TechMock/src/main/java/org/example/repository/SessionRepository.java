package org.example.repository;

import org.example.entity.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<InterviewSession, String> {

    List<InterviewSession> findByUserIdOrderByCreatedAtDesc(String userId);

    List<InterviewSession> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    long countByUserId(String userId);
}
