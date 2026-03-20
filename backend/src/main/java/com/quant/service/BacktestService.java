package com.quant.service;

import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BacktestService {

    private final UserRepository userRepository;

    public Map<String, Object> runBacktest(String username, Map<String, Object> request) {
        userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 实际应调用 Python 量化引擎，这里返回模拟结果
        Map<String, Object> result = new HashMap<>();
        result.put("totalReturn", "15.3%");
        result.put("sharpeRatio", 1.85);
        result.put("maxDrawdown", "-8.2%");
        result.put("totalTrades", 42);
        result.put("winRate", 0.58);
        result.put("finalEquity", 1153000);
        return result;
    }
}
