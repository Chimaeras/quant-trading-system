package com.quant.service;

import com.quant.dto.LoginRequest;
import com.quant.dto.LoginResponse;
import com.quant.entity.User;
import com.quant.exception.BusinessException;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Value("${jwt.expiration}")
    private long jwtExpirationMs;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
            .orElseThrow(() -> new BusinessException("用户不存在"));

        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        String token = jwtTokenProvider.generateToken(authentication);
        return new LoginResponse(token, jwtExpirationMs / 1000);
    }
}
