package com.quant.repository;

import com.quant.entity.StockInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockInfoRepository extends JpaRepository<StockInfo, Long> {

    Optional<StockInfo> findByStockCodeAndExchangeType(String stockCode, String exchangeType);

    Optional<StockInfo> findFirstByStockCode(String stockCode);

    @Query("""
            select s from StockInfo s
            where (:exchange is null or :exchange = '' or s.exchangeType = :exchange)
              and (
                :keyword is null or :keyword = ''
                or lower(s.stockCode) like lower(concat('%', :keyword, '%'))
                or lower(s.stockName) like lower(concat('%', :keyword, '%'))
              )
            """)
    Page<StockInfo> search(@Param("keyword") String keyword, @Param("exchange") String exchange, Pageable pageable);

    long countByListDateIsNull();

    List<StockInfo> findByListDateIsNull();

    /**
     * 按 (stockCode, exchangeType) 批量查询，用于选股结果行业集中度聚合。
     */
    @Query("select s from StockInfo s where concat(s.stockCode, '|', s.exchangeType) in :keys")
    List<StockInfo> findByStockKeyIn(@Param("keys") List<String> keys);
}
