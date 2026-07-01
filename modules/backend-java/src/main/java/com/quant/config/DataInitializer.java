package com.quant.config;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuthProperties authProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername(authProperties.getDefaultAdminUsername())) {
            User admin = new User();
            admin.setUsername(authProperties.getDefaultAdminUsername());
            admin.setPassword(new BCryptPasswordEncoder().encode(authProperties.getDefaultAdminPassword()));
            userRepository.save(admin);
            authService.ensureSimAccount(admin.getId());
            log.info("已创建默认管理员账户: {}", authProperties.getDefaultAdminUsername());
        }
    }
}
