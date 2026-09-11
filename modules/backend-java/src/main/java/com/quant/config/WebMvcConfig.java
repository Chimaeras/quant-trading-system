package com.quant.config;

import com.quant.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * 受控 CORS：默认前后端同源部署（SPA 由 SpaController 托管），无需额外 CORS 头。
     * 当前端独立部署时，通过 quant.cors.allowed-origins 配置显式允许的来源（逗号分隔），
     * 不使用通配符 {@code *}，避免凭证型请求的跨站风险。
     */
    @Value("${quant.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return;
        }
        String[] origins = allowedOrigins.split("\\s*,\\s*");
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // deny-by-default（V2-MP-010 P0 修复）：鉴权开启（quant.auth.enabled=true）时，
        // 除下方白名单外，所有 /api/** 接口都必须携带有效 token，且 userId 一律取自 AuthContext
        // （禁止 controller 用请求参数/默认 userId 冒认身份）。
        // 鉴权关闭（本地单用户）时拦截器为 no-op，行为与原来一致。
        // 白名单仅保留：登录/注册 + 未登录也可浏览的只读行情/分析/日报。其余一切接口
        // （账户、持仓、订单、自选、数据导入/回填、选股运行与模板、研究写操作、回测、
        //  预测、因子、对账等）默认受保护，新增接口无需再手动加白名单。
        registry.addInterceptor(authInterceptor)
                // /pyapi/** 会返回 Paper Live 账户、委托和交易动作，同样属于
                // 敏感数据；鉴权开启时不得绕过 /api/** 的 deny-by-default。
                .addPathPatterns("/api/**", "/pyapi/**")
                .excludePathPatterns(
                        // 登录/注册
                        "/api/auth/**",
                        // 只读行情与公开查询（登录页/未登录态可浏览）
                        "/api/market/**",
                        "/api/analysis/**",
                        // 日报：只放行只读端点。此前整体放行 /api/report/**，把
                        // POST /api/report/daily/realize（追加 realization 落库）也暴露成免登录写接口。
                        "/api/report/daily",
                        "/api/report/daily/versions",
                        "/api/report/trend",
                        // V2-MP-028：系统就绪度（只读聚合，顶栏健康在任何鉴权态可观测）
                        "/api/system/**");
    }
}
