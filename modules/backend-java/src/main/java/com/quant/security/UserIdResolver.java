package com.quant.security;

import com.quant.config.AuthProperties;
import com.quant.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 统一的 userId 解析：鉴权开启时一律取 {@link AuthContext}（忽略客户端传入值，防 IDOR）；
 * 鉴权关闭时（本地单用户模式）回退到显式 userId，缺省为 1。
 */
@Component
@RequiredArgsConstructor
public class UserIdResolver {

    private final AuthProperties authProperties;

    public Long resolve(Long explicitUserId) {
        if (authProperties.isEnabled()) {
            Long ctx = AuthContext.getUserId();
            if (ctx == null) {
                throw new BusinessException("未登录");
            }
            return ctx;
        }
        return explicitUserId != null ? explicitUserId : 1L;
    }
}
