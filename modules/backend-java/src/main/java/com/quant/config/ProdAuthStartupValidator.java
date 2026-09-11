package com.quant.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>R32：删除 dev profile 豁免。base application.yml 硬编码 {@code spring.profiles.active: dev}，
 * 而 dev 不是「证明本地」的可靠信号——只设 {@code AUTH_ENABLED=true}、不改 profile 的部署同样命中
 * dev，于是开发默认的公开密钥被静默接受，任何人都能用它签发任意 userId 的 token。
 * 现在只要鉴权开启就必须提供真实密钥/口令/管理员用户名，与 profile 无关。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProdAuthStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdAuthStartupValidator.class);

    /** 与 application.yml 开发默认一致，禁止在鉴权启用时沿用。 */
    static final String DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production-32b";
    static final String LEGACY_DEV_JWT_SECRET = "quant-dev-secret-change-me-in-production";
    static final String DEV_ADMIN_PASSWORD = "admin123";

    private final Environment environment;
    private final AuthProperties authProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!authProperties.isEnabled()) {
            // 鉴权整体关闭时无法做任何身份约束：userId 可由请求参数指定（UserIdResolver 单用户模式）。
            // 这里不做 fail-fast（现存部署存在以 AUTH_ENABLED=false 运行的情形，属运维决策），
            // 但必须在日志里明确暴露，避免「以为开了鉴权」的静默裸奔。
            if (isNonLocalProfile()) {
                log.warn("鉴权已关闭（quant.auth.enabled=false）且运行于非本地 profile {}："
                                + "所有 /api/** 与 /pyapi/** 端点无需 token 即可访问，且 userId 可由请求参数指定。"
                                + "对外提供服务请设置 AUTH_ENABLED=true。",
                        Arrays.toString(environment.getActiveProfiles()));
            }
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

        // prod profile 未给 username 默认值（空串），漏配会建出用户名为空的 admin 账户。
        if (!StringUtils.hasText(authProperties.getDefaultAdminUsername())) {
            throw new IllegalStateException(
                    "鉴权已启用但 DEFAULT_ADMIN_USERNAME 缺失/空白，将建出用户名为空的管理员账户。"
                            + "请通过环境变量 DEFAULT_ADMIN_USERNAME 指定管理员用户名。");
        }
    }

    /** 非本地部署 profile（prod / docker 任一激活）。 */
    private boolean isNonLocalProfile() {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        return profiles.contains("prod") || profiles.contains("docker");
    }
}
