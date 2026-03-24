package com.quant.repository;

import com.quant.entity.StockInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockInfoRepository extends JpaRepository<StockInfo, Long> {

    Optional<StockInfo> findByStockCodeAndExchangeType(String stockCode, String exchangeType);

    Optional<StockInfo> findFirstByStockCode(String stockCode);
}
