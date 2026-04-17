package com.quant.repository;

import com.quant.entity.StockMoneyFlowCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface StockMoneyFlowCacheRepository extends JpaRepository<StockMoneyFlowCache, Long> {

    Optional<StockMoneyFlowCache> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);
}
