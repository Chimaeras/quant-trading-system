package com.quant.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 前端路由回退，确保刷新或直接访问子路径时能正确加载 index.html
 * 覆盖所有前端路由，静态资源(/assets 等)由 Spring 默认静态处理器处理
 */
@Controller
public class SpaController {

    @GetMapping(value = {
        "/", "/login", "/account", "/dashboard", "/portfolio", "/report", "/settings",
        "/data/**", "/market/**", "/strategy/**", "/trade/**", "/backtest", "/trading"
    })
    public String index() {
        return "forward:/index.html";
    }

    /** 避免 favicon.ico 404 报错 */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
