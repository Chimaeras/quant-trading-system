package com.quant.config;

import com.quant.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 鉴权开启（quant.auth.enabled=true）时，以下路径需携带有效 token 且 userId 取自 AuthContext；
        // 鉴权关闭（本地单用户）时拦截器不生效，行为与原来一致。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(
                        "/api/account/**",
                        "/api/position/**",
                        "/api/order/**",
                        "/api/watchlist/**",
                        // 数据管理与导入（写/初始化接口，防止未授权触发重算/拉外网）
                        "/api/data/history/**",
                        "/api/data/financial/**",
                        "/api/data/margin/**",
                        "/api/data/backfill-raw",
                        "/api/data/security-master/**",
                        "/api/data/trading-calendar/**",
                        "/api/data/index/**",
                        "/api/data/snapshot/**",
                        "/api/data/backfill-list-date");
    }
}
