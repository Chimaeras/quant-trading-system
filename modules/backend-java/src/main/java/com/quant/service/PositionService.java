package com.quant.service;

import com.quant.entity.Account;
import com.quant.entity.Position;
import com.quant.repository.AccountRepository;
import com.quant.repository.PositionRepository;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public List<Map<String, Object>> getPositionList(String username) {
        Long userId = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("用户不存在"))
            .getId();
        Account account = accountRepository.findByUserId(userId)
            .orElseThrow(() -> new RuntimeException("账户不存在"));

        List<Position> positions = positionRepository.findByAccountIdAndVolumeGreaterThan(account.getId(), 0);

        if (positions.isEmpty()) {
            return List.of(
                createMockPosition("000001", "平安银行", 1000, 12.5, 12.8, 1000000),
                createMockPosition("600519", "贵州茅台", 100, 1750.0, 1800.0, 500000),
                createMockPosition("000858", "五粮液", 500, 175.0, 180.5, 800000)
            );
        }

        return positions.stream()
            .map(this::positionToMap)
            .collect(Collectors.toList());
    }

    private Map<String, Object> positionToMap(Position p) {
        return Map.of(
            "symbol", p.getSymbol(),
            "name", p.getName() != null ? p.getName() : p.getSymbol(),
            "volume", p.getVolume(),
            "avgPrice", p.getAvgPrice(),
            "currentPrice", p.getCurrentPrice() != null ? p.getCurrentPrice() : p.getAvgPrice(),
            "marketValue", p.getMarketValue() != null ? p.getMarketValue() : java.math.BigDecimal.ZERO
        );
    }

    private Map<String, Object> createMockPosition(String symbol, String name, int volume,
                                                    double avgPrice, double currentPrice, int dailyVolume) {
        return Map.of(
            "symbol", symbol,
            "name", name,
            "volume", volume,
            "avgPrice", avgPrice,
            "currentPrice", currentPrice,
            "volumeStr", (dailyVolume / 10000) + "万"
        );
    }
}
