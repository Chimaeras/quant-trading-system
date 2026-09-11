package com.quant.service;

import com.quant.config.AuthProperties;
import com.quant.entity.User;
import com.quant.entity.UserAccount;
import com.quant.exception.BusinessException;
import com.quant.repository.UserAccountRepository;
import com.quant.repository.UserRepository;
import com.quant.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** 与 {@code user.username} 列宽（VARCHAR(50)）一致。 */
    private static final int USERNAME_MAX_LENGTH = 50;

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public Map<String, Object> login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("账户已禁用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        ensureSimAccount(user.getId());
        String token = jwtService.createToken(user.getId(), user.getUsername());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresIn", jwtService.getExpiresInSeconds());
        data.put("username", user.getUsername());
        data.put("userId", user.getId());
        return data;
    }

    @Transactional
    public Map<String, Object> register(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new BusinessException("用户名不能为空且密码至少 6 位");
        }
        // 长度按列宽前置校验：/api/auth/** 免鉴权可达，超长用户名会在提交期抛
        // DataIntegrityViolationException（未映射 → 500），本应是一个 400。
        if (username.trim().length() > USERNAME_MAX_LENGTH) {
            throw new BusinessException("用户名长度不能超过 " + USERNAME_MAX_LENGTH + " 个字符");
        }
        if (userRepository.existsByUsername(username.trim())) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        createSimAccount(user.getId(), authProperties.getDefaultSimCash());
        return login(username.trim(), password);
    }

    public void ensureSimAccount(Long userId) {
        userAccountRepository.findFirstByUserIdAndStatus(userId, 1)
                .orElseGet(() -> createSimAccount(userId, authProperties.getDefaultSimCash()));
    }

    private UserAccount createSimAccount(Long userId, double cash) {
        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setAccountName("模拟账户");
        account.setAccountType(1);
        BigDecimal init = BigDecimal.valueOf(cash);
        account.setAvailableCash(init);
        account.setTotalAsset(init);
        return userAccountRepository.save(account);
    }
}
