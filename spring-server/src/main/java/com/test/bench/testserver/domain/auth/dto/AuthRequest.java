package com.test.bench.testserver.domain.auth.dto;

public record AuthRequest(
        String username,
        String password
) {
}
