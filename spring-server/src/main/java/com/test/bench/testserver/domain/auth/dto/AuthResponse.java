package com.test.bench.testserver.domain.auth.dto;

public record AuthResponse(
        Long userId,
        String username
) {
}
