package com.test.bench.testserver.domain.user.service;

import com.test.bench.testserver.domain.user.dto.UserRequest;
import com.test.bench.testserver.domain.user.dto.UserResponse;
import com.test.bench.testserver.domain.user.entity.User;
import com.test.bench.testserver.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse signUp(UserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("이미 존재하는 username 입니다.");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("이미 존재하는 email 입니다.");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .password(request.password()) // 벤치마크용: 실제 서비스라면 반드시 암호화 필요
                .build();

        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다. id=" + userId));
        return UserResponse.from(user);
    }
}
