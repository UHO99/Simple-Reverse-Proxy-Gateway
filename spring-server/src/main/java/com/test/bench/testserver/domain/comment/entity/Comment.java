package com.test.bench.testserver.domain.comment.entity;

import com.test.bench.testserver.domain.board.entity.Board;
import com.test.bench.testserver.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Comment
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    private Board board;

    private LocalDateTime createdAt;

    @Builder
    public Comment(String content, User author, Board board) {
        this.content = content;
        this.author = author;
        this.board = board;
        this.createdAt = LocalDateTime.now();
    }

    public void update(String content) {
        this.content = content;
    }
}
