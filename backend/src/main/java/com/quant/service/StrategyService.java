package com.quant.service;

import com.quant.entity.Strategy;
import com.quant.repository.StrategyRepository;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StrategyService {

    private final StrategyRepository strategyRepository;
    private final UserRepository userRepository;

    public List<Map<String, Object>> getStrategyList(String username) {
        Long userId = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("用户不存在"))
            .getId();

        List<Strategy> strategies = strategyRepository.findByUserId(userId);

        if (strategies.isEmpty()) {
            return List.of(
                Map.of("id", 1, "name", "MA_Cross", "status", "running", "pnl", 12500),
                Map.of("id", 2, "name", "BOLL_Strategy", "status", "stopped", "pnl", -3200)
            );
        }

        return strategies.stream()
            .map(s -> Map.<String, Object>of(
                "id", s.getId(),
                "name", s.getName(),
                "status", s.getStatus(),
                "pnl", s.getPnl() != null ? s.getPnl() : 0
            ))
            .collect(Collectors.toList());
    }
}
