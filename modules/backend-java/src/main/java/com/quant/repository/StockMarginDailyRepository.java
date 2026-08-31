package com.quant.repository;

import com.quant.entity.StockMarginDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockMarginDailyRepository extends JpaRepository<StockMarginDaily, Long> {

    Optional<StockMarginDaily> findFirstByStockCodeAndExchangeTypeAndTradeDateLessThanEqualOrderByTradeDateDesc(
            String stockCode, String exchangeType, LocalDate tradeDate);

    /** 按唯一键 (stock_code, exchange_type, trade_date) 查找，用于批量导入幂等 upsert。 */
    Optional<StockMarginDaily> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);

    @Query("SELECT m FROM StockMarginDaily m WHERE m.stockCode = :c AND m.exchangeType = :e AND m.tradeDate <= :d ORDER BY m.tradeDate DESC")
    List<StockMarginDaily> findRecent(@Param("c") String c, @Param("e") String e, @Param("d") LocalDate d, org.springframework.data.domain.Pageable pageable);
}
