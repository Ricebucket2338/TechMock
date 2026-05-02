package org.example.repository;

import org.example.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Query("SELECT m FROM Message m WHERE m.session.id = :sessionId ORDER BY m.createdAt ASC LIMIT :limit")
    List<Message> findRecentBySession(@Param("sessionId") String sessionId, @Param("limit") int limit);

    int countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
