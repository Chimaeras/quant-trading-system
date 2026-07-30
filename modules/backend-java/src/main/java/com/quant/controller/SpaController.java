package com.quant.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    /**
     * SPA history 模式 fallback：前端使用 createWebHistory()，直接访问或刷新任意前端路由
     * 深路径时，须由后端 forward 到 index.html，否则抛 NoResourceFoundException 返回 404「资源不存在」。
     *
     * <p>注意：此处须覆盖所有前端路由前缀（见 modules/web/src/router/index.ts）。
     * 新增前端路由前缀时必须同步在此添加，否则该页面刷新即 404。
     * /api/** 由各 Controller 端点处理，静态资源（带后缀）由 ResourceHandler 处理，不会被此处吞没。
     */
    @GetMapping(value = {
            "/",
            "/data/**", "/market/**", "/backtest/**", "/account/**",
            "/report/**", "/compare/**", "/factor/**", "/reconcile/**",
            "/watchlist/**", "/screener/**",
            "/login"
    })
    public String index() {
        return "forward:/index.html";
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
