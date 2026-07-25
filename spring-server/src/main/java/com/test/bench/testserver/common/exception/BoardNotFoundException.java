package com.test.bench.testserver.common.exception;

public class BoardNotFoundException extends BusinessException {

    public BoardNotFoundException(Long boardId) {
        super(ErrorCode.BOARD_NOT_FOUND, "존재하지 않는 게시글입니다. id=" + boardId);
    }
}
