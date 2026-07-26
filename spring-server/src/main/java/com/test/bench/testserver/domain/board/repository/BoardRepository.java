package com.test.bench.testserver.domain.board.repository;

import com.test.bench.testserver.domain.board.entity.Board;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Long> {

    // N+1 비교용: author까지 fetch join 없이 LAZY 그대로 페이징 조회
    Page<Board> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // N+1 최적화 비교용: author를 fetch join으로 한 번에 조회
    @EntityGraph(attributePaths = "author")
    Optional<Board> findWithAuthorById(Long id);

    // 비관적 락 동시성 테스트용 (낙관적 락의 @Version 방식과 처리량 비교)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Board b where b.id = :id")
    Optional<Board> findByIdForUpdate(@Param("id") Long id);
}
