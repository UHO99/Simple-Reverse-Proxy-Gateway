package com.test.bench.testserver.domain.comment.service;

import com.test.bench.testserver.domain.board.entity.Board;
import com.test.bench.testserver.domain.board.repository.BoardRepository;
import com.test.bench.testserver.domain.comment.dto.CommentRequest;
import com.test.bench.testserver.domain.comment.dto.CommentResponse;
import com.test.bench.testserver.domain.comment.entity.Comment;
import com.test.bench.testserver.domain.comment.repository.CommentRepository;
import com.test.bench.testserver.domain.user.entity.User;
import com.test.bench.testserver.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    @Transactional
    public CommentResponse createComment(Long boardId, Long authorId, CommentRequest request) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글입니다. id=" + boardId));
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다. id=" + authorId));

        Comment comment = Comment.builder()
                .content(request.content())
                .author(author)
                .board(board)
                .build();

        return CommentResponse.from(commentRepository.save(comment));
    }

    public List<CommentResponse> getComments(Long boardId) {
        return commentRepository.findAllByBoardIdOrderByCreatedAtAsc(boardId)
                .stream()
                .map(CommentResponse::from)
                .toList();
    }

    @Transactional
    public CommentResponse updateComment(Long commentId, CommentRequest request) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 댓글입니다. id=" + commentId));
        comment.update(request.content());
        return CommentResponse.from(comment);
    }

    @Transactional
    public void deleteComment(Long commentId) {
        commentRepository.deleteById(commentId);
    }
}
