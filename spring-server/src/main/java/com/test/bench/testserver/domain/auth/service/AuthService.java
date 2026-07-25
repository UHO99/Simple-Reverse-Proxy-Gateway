package com.test.bench.testserver.domain.auth.service;

import com.test.bench.benchtest.domain.auth.dto.AuthRequest;
import com.test.bench.benchtest.domain.auth.dto.AuthResponse;
import com.test.bench.benchtest.domain.user.entity.User;
import com.test.bench.benchtest.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;

    // 벤치마크용 단순 로그인: 비밀번호 평문 비교 (실서비스라면 PasswordEncoder 필수)
    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 username 입니다."));

        if (!user.getPassword().equals(request.password())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        return new AuthResponse(user.getId(), user.getUsername());
    }
}
