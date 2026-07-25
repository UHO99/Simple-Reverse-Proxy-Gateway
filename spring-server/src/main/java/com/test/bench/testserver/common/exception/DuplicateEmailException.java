package com.test.bench.testserver.common.exception;

public class DuplicateEmailException extends BusinessException {

    public DuplicateEmailException(String email) {
        super(ErrorCode.DUPLICATE_EMAIL, "이미 존재하는 email 입니다. email=" + email);
    }
}
