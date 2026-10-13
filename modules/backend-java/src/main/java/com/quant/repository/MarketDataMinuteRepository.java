package com.quant.repository;

import com.quant.entity.MarketDataMinute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface MarketDataMinuteRepository extends JpaRepository<MarketDataMinute, Long> {

    /**
     * 幂等写入单根 bar。
     *
     * <p>用原生 {@code ON DUPLICATE KEY UPDATE} 而不是「先查后存」：盯盘每轮会提交
     * 数百根 bar，逐根 SELECT 会放大成数百次往返；且重启/重跑必然重复提交同一根 bar，
     * 必须靠唯一键收敛而不是靠调用方去重。
     */
    @Modifying
    @Query(value = """
            INSERT INTO market_data_minute
                (stock_code, period_min, start_ts, end_ts, trade_date,
                 open_price, high_price, low_price, close_price, volume, source,
                 created_at, updated_at)
            VALUES (:stockCode, :periodMin, :startTs, :endTs, :tradeDate,
                    :openPrice, :highPrice, :lowPrice, :closePrice, :volume, :source,
                    NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                end_ts = VALUES(end_ts),
                open_price = VALUES(open_price),
                high_price = VALUES(high_price),
                low_price = VALUES(low_price),
                close_price = VALUES(close_price),
                volume = VALUES(volume),
                source = VALUES(source),
                updated_at = NOW()
            """, nativeQuery = true)
    void upsert(
            @Param("stockCode") String stockCode,
            @Param("periodMin") Integer periodMin,
            @Param("startTs") LocalDateTime startTs,
            @Param("endTs") LocalDateTime endTs,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("openPrice") BigDecimal openPrice,
            @Param("highPrice") BigDecimal highPrice,
            @Param("lowPrice") BigDecimal lowPrice,
            @Param("closePrice") BigDecimal closePrice,
            @Param("volume") Long volume,
            @Param("source") String source);

    /**
     * 取一批代码在 {@code fromDate} 之后的全部 bar，升序返回。
     *
     * <p>调用方（预热）再按代码取尾部 N 根。用「日期窗口 + 升序全量」而非窗口函数，
     * 是为了让 Hibernate 走实体映射、避免原生查询里 DATE/DATETIME/DECIMAL 的
     * 手工类型转换；窗口由 {@code fromDate} 界定，行数可控（约 代码数 × N 天 × 48）。
     */
    List<MarketDataMinute> findByPeriodMinAndStockCodeInAndTradeDateGreaterThanEqualOrderByStockCodeAscStartTsAsc(
            Integer periodMin, Collection<String> stockCodes, LocalDate fromDate);

    /** 该周期已沉淀的最新 bar 时刻，用于监控「分钟线是否在持续入库」。 */
    @Query("SELECT MAX(m.startTs) FROM MarketDataMinute m WHERE m.periodMin = :periodMin")
    LocalDateTime findLatestStartTs(@Param("periodMin") Integer periodMin);

    long countByPeriodMin(Integer periodMin);

    /** 清理窗口外的陈旧 bar，保持表不无限增长。 */
    @Modifying
    @Query("DELETE FROM MarketDataMinute m WHERE m.periodMin = :periodMin AND m.tradeDate < :cutoff")
    int deleteOlderThan(@Param("periodMin") Integer periodMin, @Param("cutoff") LocalDate cutoff);
}
