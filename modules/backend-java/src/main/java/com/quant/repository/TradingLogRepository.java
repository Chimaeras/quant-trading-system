package com.quant.repository;

import com.quant.entity.TradingLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingLogRepository extends JpaRepository<TradingLog, Long> {
}
