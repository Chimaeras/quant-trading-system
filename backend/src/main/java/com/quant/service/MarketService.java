package com.quant.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class MarketService {

    private final Random random = new Random();

    public List<Map<String, Object>> getKlineData(String symbol, String interval, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        double basePrice = symbol.equals("600519") ? 1750 : 12.5;
        double price = basePrice;

        for (int i = limit; i > 0; i--) {
            price = price * (1 + (random.nextDouble() - 0.5) * 0.02);
            result.add(Map.of(
                "time", "2026-03-" + String.format("%02d", 21 - i),
                "open", basePrice,
                "high", price * 1.01,
                "low", price * 0.99,
                "close", price,
                "volume", 1000000 + random.nextInt(500000)
            ));
            basePrice = price;
        }
        return result;
    }
}
