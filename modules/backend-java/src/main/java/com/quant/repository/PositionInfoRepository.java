package com.quant.repository;

import com.quant.entity.PositionInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionInfoRepository extends JpaRepository<PositionInfo, Long> {
    List<PositionInfo> findByAccountIdOrderByStockCodeAsc(Long accountId);
    Optional<PositionInfo> findByAccountIdAndStockCodeAndExchangeType(Long accountId, String stockCode, String exchangeType);
}
