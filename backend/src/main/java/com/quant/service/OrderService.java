package com.quant.service;

import com.quant.entity.Account;
import com.quant.entity.Order;
import com.quant.exception.BusinessException;
import com.quant.repository.AccountRepository;
import com.quant.repository.OrderRepository;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public Map<String, Object> placeOrder(String username, Map<String, Object> orderRequest) {
        Long userId = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException("用户不存在"))
            .getId();
        Account account = accountRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException("账户不存在"));

        String symbol = (String) orderRequest.get("symbol");
        String action = (String) orderRequest.get("action");
        int volume = ((Number) orderRequest.get("volume")).intValue();
        double price = ((Number) orderRequest.getOrDefault("price", 0)).doubleValue();
        String type = (String) orderRequest.getOrDefault("type", "limit");

        String orderId = "ORD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", (int) (Math.random() * 10000));

        Order order = new Order();
        order.setOrderId(orderId);
        order.setAccountId(account.getId());
        order.setSymbol(symbol);
        order.setAction(action);
        order.setType(type);
        order.setVolume(volume);
        order.setPrice(BigDecimal.valueOf(price));
        order.setAmount(BigDecimal.valueOf(volume * price));
        order.setStatus("submitted");
        orderRepository.save(order);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("status", "submitted");
        return result;
    }
}
