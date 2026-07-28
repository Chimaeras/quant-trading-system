package com.quant.repository;

import com.quant.entity.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    List<WatchlistItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<WatchlistItem> findByUserIdAndStockCodeAndExchangeType(Long userId, String stockCode, String exchangeType);

    long deleteByUserIdAndStockCodeAndExchangeType(Long userId, String stockCode, String exchangeType);
}
