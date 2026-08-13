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
 * 启动校验：只要鉴权开启（quant.auth.enabled=true），即拒绝缺失/空白/开发默认 JWT 密钥
 * 与默认管理员口令。覆盖 prod 与 docker 等一切「非本地 dev」部署路径——docker profile
 * 默认开启鉴权，此前因仅校验 prod 而回退开发默认密钥，存在伪造 token 风险。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProdAuthStartupValidator implements ApplicationRunner {

    /** 与 application.yml 开发默认一致，禁止在鉴权启用时沿用。 */
    static final String DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production-32b";
    static final String LEGACY_DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production";
    static final String DEV_ADMIN_PASSWORD = "admin123";

    private final Environment environment;
    private final AuthProperties authProperties;

    @Override
    public void run(ApplicationArguments args) {
        // 只要鉴权开启就校验，不再仅限 prod profile（docker 等非 dev 部署同样需要保护）。
        if (!authProperties.isEnabled()) {
            return;
        }

        // 仅本地 dev（未显式指定 profile）允许豁免开发默认凭证。
        if (isLocalDevActive()) {
            return;
        }

        String jwtSecret = authProperties.getJwtSecret();
        if (!StringUtils.hasText(jwtSecret)
                || DEV_JWT_SECRET.equals(jwtSecret)
                || LEGACY_DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "鉴权已启用但 JWT_SECRET 缺失/空白或仍为开发默认值。"
                            + "请通过环境变量 JWT_SECRET 设置足够长的随机密钥（≥32 字节）。");
        }

        String adminPassword = authProperties.getDefaultAdminPassword();
        if (!StringUtils.hasText(adminPassword) || DEV_ADMIN_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException(
                    "鉴权已启用但管理员口令缺失/空白或仍为默认值 admin123。"
                            + "请通过环境变量 DEFAULT_ADMIN_PASSWORD 设置强口令。");
        }
    }

    /**
     * 判断是否为本地 dev：没有任何激活 profile（默认 dev），或显式激活了 dev profile。
     * 只有这种情况下才允许用开发默认凭证启动鉴权（仍会打 warn 日志）。
     */
    private boolean isLocalDevActive() {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.isEmpty()) {
            profiles.addAll(Arrays.asList(environment.getDefaultProfiles()));
        }
        return profiles.contains("dev");
    }
}
