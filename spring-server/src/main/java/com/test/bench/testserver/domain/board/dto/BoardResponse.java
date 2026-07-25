package com.test.bench.testserver.domain.board.dto;

import com.test.bench.benchtest.domain.board.entity.Board;

import java.time.LocalDateTime;

public record BoardResponse(
        Long id,
        String title,
        String content,
        long viewCount,
        Long authorId,
        String authorUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static BoardResponse from(Board board) {
        return new BoardResponse(
                board.getId(),
                board.getTitle(),
                board.getContent(),
                board.getViewCount(),
                board.getAuthor().getId(),
                board.getAuthor().getUsername(),
                board.getCreatedAt(),
                board.getUpdatedAt()
        );
    }
}
