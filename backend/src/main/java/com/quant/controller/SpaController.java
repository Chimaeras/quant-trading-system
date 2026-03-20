package com.quant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 前端路由回退，确保刷新或直接访问子路径时能正确加载 index.html
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/", "/login", "/dashboard", "/strategy", "/backtest", "/trading", "/account"})
    public String index() {
        return "forward:/index.html";
    }
}
