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
        // deny-by-default（V2-MP-010 P0 修复）：鉴权开启（quant.auth.enabled=true）时，
        // 除下方白名单外，所有 /api/** 接口都必须携带有效 token，且 userId 一律取自 AuthContext
        // （禁止 controller 用请求参数/默认 userId 冒认身份）。
        // 鉴权关闭（本地单用户）时拦截器为 no-op，行为与原来一致。
        // 白名单仅保留：登录/注册 + 未登录也可浏览的只读行情/分析/日报。其余一切接口
        // （账户、持仓、订单、自选、数据导入/回填、选股运行与模板、研究写操作、回测、
        //  预测、因子、对账等）默认受保护，新增接口无需再手动加白名单。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 登录/注册
                        "/api/auth/**",
                        // 只读行情与公开查询（登录页/未登录态可浏览）
                        "/api/market/**",
                        "/api/analysis/**",
                        "/api/report/**",
                        // V2-MP-028：系统就绪度（只读聚合，顶栏健康在任何鉴权态可观测）
                        "/api/system/**");
    }
}
