package com.quant.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "quant.auth")
public class AuthProperties {
    /** 是否启用 JWT 鉴权（默认关闭，不影响现有接口） */
    private boolean enabled = false;
    private String jwtSecret = "quant-dev-secret-change-me-in-production-32b";
    private long jwtExpirationHours = 72;
    private String defaultAdminUsername = "admin";
    private String defaultAdminPassword = "admin123";
    private double defaultSimCash = 1_000_000;

    /** 开发默认密钥/口令，仅用于本地；生产由 ProdAuthStartupValidator 硬拒绝。 */
    private static final String DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production-32b";
    private static final String DEV_ADMIN_PASSWORD = "admin123";

    @PostConstruct
    void warnOnDevCredentials() {
        if (enabled && (DEV_JWT_SECRET.equals(jwtSecret) || DEV_ADMIN_PASSWORD.equals(defaultAdminPassword))) {
            log.warn("⚠ 鉴权已启用但仍在使用开发默认 JWT 密钥/管理员口令。生产部署必须通过 "
                    + "JWT_SECRET / DEFAULT_ADMIN_PASSWORD 环境变量覆盖，否则存在被攻破风险。");
        }
    }
}
