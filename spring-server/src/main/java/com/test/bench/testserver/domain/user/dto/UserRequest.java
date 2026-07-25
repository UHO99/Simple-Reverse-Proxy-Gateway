package com.test.bench.testserver.domain.user.dto;

public record UserRequest(
        String username,
        String email,
        String password
) {
}
