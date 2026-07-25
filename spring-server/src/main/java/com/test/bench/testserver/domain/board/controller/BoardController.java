package com.test.bench.testserver.domain.board.controller;

import com.test.bench.benchtest.domain.board.dto.BoardRequest;
import com.test.bench.benchtest.domain.board.dto.BoardResponse;
import com.test.bench.benchtest.domain.board.service.BoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @PostMapping
    public ResponseEntity<BoardResponse> createBoard(@RequestParam Long authorId,
                                                       @RequestBody BoardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(boardService.createBoard(authorId, request));
    }

    @GetMapping
    public ResponseEntity<Page<BoardResponse>> getBoards(Pageable pageable) {
        return ResponseEntity.ok(boardService.getBoards(pageable));
    }

    // N+1 발생 케이스 (LAZY author, fetch join 없음) - 벤치마크 비교용
    @GetMapping("/{boardId}")
    public ResponseEntity<BoardResponse> getBoard(@PathVariable Long boardId) {
        return ResponseEntity.ok(boardService.getBoard(boardId));
    }

    // N+1 최적화 케이스 (fetch join) - 벤치마크 비교용
    @GetMapping("/{boardId}/with-author")
    public ResponseEntity<BoardResponse> getBoardWithAuthor(@PathVariable Long boardId) {
        return ResponseEntity.ok(boardService.getBoardWithAuthor(boardId));
    }

    @PutMapping("/{boardId}")
    public ResponseEntity<BoardResponse> updateBoard(@PathVariable Long boardId,
                                                       @RequestBody BoardRequest request) {
        return ResponseEntity.ok(boardService.updateBoard(boardId, request));
    }

    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> deleteBoard(@PathVariable Long boardId) {
        boardService.deleteBoard(boardId);
        return ResponseEntity.noContent().build();
    }

    // 쓰기 동시성 벤치마크용: 낙관적 락 방식 조회수 증가
    @PostMapping("/{boardId}/view")
    public ResponseEntity<Void> increaseViewCount(@PathVariable Long boardId) {
        boardService.increaseViewCount(boardId);
        return ResponseEntity.ok().build();
    }

    // 쓰기 동시성 벤치마크용: 비관적 락 방식 조회수 증가
    @PostMapping("/{boardId}/view/lock")
    public ResponseEntity<Void> increaseViewCountWithLock(@PathVariable Long boardId) {
        boardService.increaseViewCountWithLock(boardId);
        return ResponseEntity.ok().build();
    }
}
