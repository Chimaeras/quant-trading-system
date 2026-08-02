package com.quant.security;

import com.quant.config.AuthProperties;
import com.quant.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 鉴权开启时忽略客户端传入 userId（防 IDOR），关闭时回退显式/默认值。
 */
class UserIdResolverTest {

    @Test
    void resolveIgnoresExplicitUserIdWhenAuthEnabled() {
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        UserIdResolver resolver = new UserIdResolver(props);
        AuthContext.setUserId(42L);
        try {
            assertThat(resolver.resolve(999L)).isEqualTo(42L);
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void resolveThrowsWhenAuthEnabledButNoContext() {
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        UserIdResolver resolver = new UserIdResolver(props);
        AuthContext.clear();
        assertThatThrownBy(() -> resolver.resolve(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveFallsBackToExplicitOrOneWhenAuthDisabled() {
        AuthProperties props = new AuthProperties();
        props.setEnabled(false);
        UserIdResolver resolver = new UserIdResolver(props);
        assertThat(resolver.resolve(7L)).isEqualTo(7L);
        assertThat(resolver.resolve(null)).isEqualTo(1L);
    }
}
