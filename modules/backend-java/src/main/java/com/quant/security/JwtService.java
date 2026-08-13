package com.quant.security;

import com.quant.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final AuthProperties authProperties;

    public String createToken(Long userId, String username) {
        Instant now = Instant.now();
        Instant exp = now.plus(authProperties.getJwtExpirationHours(), ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey())
                .compact();
    }

    public Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.parseLong(claims.getSubject());
    }

    public long getExpiresInSeconds() {
        return java.time.Duration.ofHours(authProperties.getJwtExpirationHours()).toSeconds();
    }

    private SecretKey secretKey() {
        byte[] bytes = authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        // HS256 安全下限为 32 字节（256 bit）。短密钥静默补零会削弱签名强度，
        // 这里改为直接拒绝——密钥不足时 fail-fast，避免运维误配弱密钥而不自知。
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET 长度不足 32 字节（当前 " + bytes.length
                            + "），HS256 要求至少 256 bit 密钥。请设置更强的密钥。");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
