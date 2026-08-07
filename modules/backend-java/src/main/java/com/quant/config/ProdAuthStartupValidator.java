package com.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 生产 profile 启动校验：拒绝缺失/空白/开发默认 JWT 密钥与默认管理员口令。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProdAuthStartupValidator implements ApplicationRunner {

    /** 与 application.yml 开发默认一致，禁止在 prod 沿用。 */
    static final String DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production-32b";
    static final String LEGACY_DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production";
    static final String DEV_ADMIN_PASSWORD = "admin123";

    private final Environment environment;
    private final AuthProperties authProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!isProdActive()) {
            return;
        }

        String jwtSecret = authProperties.getJwtSecret();
        if (!StringUtils.hasText(jwtSecret)
                || DEV_JWT_SECRET.equals(jwtSecret)
                || LEGACY_DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "prod 环境禁止使用缺失/空白或开发默认 JWT_SECRET。"
                            + "请设置环境变量 JWT_SECRET 为足够长的随机密钥。");
        }

        String adminPassword = authProperties.getDefaultAdminPassword();
        if (!StringUtils.hasText(adminPassword) || DEV_ADMIN_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException(
                    "prod 环境禁止使用空白或默认管理员口令 admin123。"
                            + "请设置环境变量 DEFAULT_ADMIN_PASSWORD。");
        }
    }

    private boolean isProdActive() {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.isEmpty()) {
            profiles.addAll(Arrays.asList(environment.getDefaultProfiles()));
        }
        return profiles.contains("prod");
    }
}
