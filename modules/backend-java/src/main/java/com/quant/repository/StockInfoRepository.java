package com.quant.repository;

import com.quant.entity.StockInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
