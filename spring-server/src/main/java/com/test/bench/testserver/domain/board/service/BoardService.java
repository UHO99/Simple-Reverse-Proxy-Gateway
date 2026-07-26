package com.test.bench.testserver.domain.board.service;

import com.test.bench.testserver.domain.board.dto.BoardRequest;
import com.test.bench.testserver.domain.board.dto.BoardResponse;
import com.test.bench.testserver.domain.board.entity.Board;
import com.test.bench.testserver.domain.board.repository.BoardRepository;
import com.test.bench.testserver.domain.user.entity.User;
import com.test.bench.testserver.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    @Transactional
    public BoardResponse createBoard(Long authorId, BoardRequest request) {
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다. id=" + authorId));

        Board board = Board.builder()
                .title(request.title())
                .content(request.content())
                .author(author)
                .build();

        return BoardResponse.from(boardRepository.save(board));
    }

    // N+1 발생 가능 케이스: author가 LAZY 상태로 조회 후 DTO 변환 시점에 추가 SELECT 발생
    public BoardResponse getBoard(Long boardId) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글입니다. id=" + boardId));
        return BoardResponse.from(board);
    }

    // N+1 최적화 케이스: fetch join으로 author를 함께 조회
    public BoardResponse getBoardWithAuthor(Long boardId) {
        Board board = boardRepository.findWithAuthorById(boardId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글입니다. id=" + boardId));
        return BoardResponse.from(board);
    }

    public Page<BoardResponse> getBoards(Pageable pageable) {
        return boardRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(BoardResponse::from);
    }

    @Transactional
    public BoardResponse updateBoard(Long boardId, BoardRequest request) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글입니다. id=" + boardId));
        board.update(request.title(), request.content());
        return BoardResponse.from(board);
    }

    @Transactional
    public void deleteBoard(Long boardId) {
        boardRepository.deleteById(boardId);
    }

    // 쓰기 동시성 벤치마크용: 단순 조회수 증가 (낙관적 락, @Version 충돌 시 재시도는 호출 측에서 처리)
    @Transactional
    public void increaseViewCount(Long boardId) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글입니다. id=" + boardId));
        board.increaseViewCount();
    }

    // 쓰기 동시성 벤치마크용: 비관적 락 버전 (낙관적 락 재시도 처리량과 비교용)
    @Transactional
    public void increaseViewCountWithLock(Long boardId) {
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글입니다. id=" + boardId));
        board.increaseViewCount();
    }
}
