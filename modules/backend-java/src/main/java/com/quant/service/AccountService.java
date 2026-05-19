package com.quant.service;

import com.quant.entity.Account;
import com.quant.exception.BusinessException;
import com.quant.repository.AccountRepository;
import com.quant.repository.PositionRepository;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final UserRepository userRepository;

    public Map<String, Object> getAccountInfo(String username) {
        Long userId = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException("用户不存在"))
            .getId();

        Account account = accountRepository.findByUserId(userId)
            .orElseGet(() -> createDefaultAccount(userId));

        int positionCount = positionRepository.countByAccountId(account.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("totalAsset", account.getTotalAsset());
        result.put("cash", account.getCash());
        result.put("positionValue", account.getPositionValue());
        result.put("todayPnl", account.getTodayPnl());
        result.put("positionCount", positionCount);
        return result;
    }

    private Account createDefaultAccount(Long userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setTotalAsset(java.math.BigDecimal.valueOf(500000));
        account.setCash(java.math.BigDecimal.valueOf(150000));
        account.setPositionValue(java.math.BigDecimal.valueOf(350000));
        account.setTodayPnl(java.math.BigDecimal.valueOf(12500));
        return accountRepository.save(account);
    }
}
