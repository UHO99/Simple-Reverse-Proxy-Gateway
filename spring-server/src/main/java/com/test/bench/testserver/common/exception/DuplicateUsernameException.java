package com.test.bench.testserver.common.exception;

public class DuplicateUsernameException extends BusinessException {

    public DuplicateUsernameException(String username) {
        super(ErrorCode.DUPLICATE_USERNAME, "이미 존재하는 username 입니다. username=" + username);
    }
}
