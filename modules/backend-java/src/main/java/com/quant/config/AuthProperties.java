package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "quant.auth")
public class AuthProperties {
    /** 是否启用 JWT 鉴权（默认关闭，不影响现有接口） */
    private boolean enabled = false;
    private String jwtSecret = "quant-dev-secret-change-me-in-production";
    private long jwtExpirationHours = 72;
    private String defaultAdminUsername = "admin";
    private String defaultAdminPassword = "admin123";
    private double defaultSimCash = 1_000_000;
}
