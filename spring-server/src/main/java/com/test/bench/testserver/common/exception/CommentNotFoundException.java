package com.test.bench.testserver.common.exception;

public class CommentNotFoundException extends BusinessException {

    public CommentNotFoundException(Long commentId) {
        super(ErrorCode.COMMENT_NOT_FOUND, "존재하지 않는 댓글입니다. id=" + commentId);
    }
}
