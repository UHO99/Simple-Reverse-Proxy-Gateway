package com.test.bench.testserver.common.exception;

public class UserNotFoundException extends BusinessException {

    public UserNotFoundException(Long userId) {
        super(ErrorCode.USER_NOT_FOUND, "존재하지 않는 유저입니다. id=" + userId);
    }

    public UserNotFoundException(String username) {
        super(ErrorCode.USER_NOT_FOUND, "존재하지 않는 유저입니다. username=" + username);
    }
}
