package com.test.bench.testserver.domain.comment.repository;

import com.test.bench.testserver.domain.comment.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findAllByBoardIdOrderByCreatedAtAsc(Long boardId);

    long countByBoardId(Long boardId);
}
