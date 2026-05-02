package org.example.repository;

import org.example.entity.CandidateSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CandidateSkillRepository extends JpaRepository<CandidateSkill, Long> {

    List<CandidateSkill> findByUserId(String userId);

    List<CandidateSkill> findByUserIdAndSessionId(String userId, String sessionId);

    List<CandidateSkill> findByUserIdAndSkillId(String userId, String skillId);

    boolean existsByUserIdAndSkillId(String userId, String skillId);
}
