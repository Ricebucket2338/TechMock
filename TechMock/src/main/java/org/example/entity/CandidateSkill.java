package org.example.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_skill",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_user_skill_session",
           columnNames = {"user_id", "skill_id", "session_id"}))
@Getter
@Setter
public class CandidateSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private InterviewSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false)
    private int assessedLevel;

    private Float confidence;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String evidence;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
