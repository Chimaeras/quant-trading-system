package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 市场环境分析：avgMarketReturn / marketBreadth 须用前一交易日（而非窗口内最早日）收盘计算。
 */
@ExtendWith(MockitoExtension.class)
class MarketEnvironmentAnalyzerTest {

    @Mock
    private MarketDataRepository marketDataRepository;

    private MarketEnvironmentAnalyzer analyzer() {
        return new MarketEnvironmentAnalyzer(marketDataRepository);
    }

    private static MarketData bar(String code, String ex, LocalDate date, double close) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setOpenPrice(BigDecimal.valueOf(close));
        m.setHighPrice(BigDecimal.valueOf(close));
        m.setLowPrice(BigDecimal.valueOf(close));
        m.setVolume(1_000_000L);
        m.setAmount(BigDecimal.valueOf(close * 1_000_000));
        return m;
    }

    /**
     * 修复前 calculateAvgMarketReturn / calculateMarketBreadth 用 !containsKey 保留窗口内最早 bar，
     * 导致市场收益/广度以最早日为基准而非前一交易日。修复后应取前一交易日收盘。
     */
    @Test
    void avgMarketReturnUsesPreviousTradingDayNotOldestInWindow() {
        LocalDate d0 = LocalDate.of(2025, 1, 1); // 窗口内最早日
        LocalDate d1 = LocalDate.of(2025, 1, 2); // 前一交易日
        LocalDate d2 = LocalDate.of(2025, 1, 3); // 信号日

        // 按 stockCode ASC, exchangeType ASC, tradeDate ASC 排序（与 Repository 一致）
        List<MarketData> bars = new ArrayList<>();
        // 股票 A：d0=10, d1=11, d2=12
        bars.add(bar("000001", "SZSE", d0, 10.0));
        bars.add(bar("000001", "SZSE", d1, 11.0));
        bars.add(bar("000001", "SZSE", d2, 12.0));
        // 股票 B：d0=20, d1=21, d2=22
        bars.add(bar("000002", "SZSE", d0, 20.0));
        bars.add(bar("000002", "SZSE", d1, 21.0));
        bars.add(bar("000002", "SZSE", d2, 22.0));

        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(bars);

        MarketEnvironmentAnalyzer.MarketIndicators mi = analyzer().analyze(d2, 60);

        // 正确口径（前一交易日 d1）：A=(12-11)/11, B=(22-21)/21 → 均值 ≈ 6.926%
        // Bug 口径（最早日 d0）：A=(12-10)/10, B=(22-20)/20 → 均值 = 15.0%
        assertThat(mi.avgMarketReturn())
                .isCloseTo(((12.0 / 11.0 - 1) + (22.0 / 21.0 - 1)) / 2 * 100,
                        org.assertj.core.data.Offset.offset(0.01))
                .isNotCloseTo(15.0, org.assertj.core.data.Offset.offset(0.5));

        // marketBreadth：两只均上涨 → 1.0
        assertThat(mi.marketBreadth()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    /**
     * 同代码不同交易所的标的不能互为「前一日」bar：
     * calculateIndexChange 查找 prevBar 时须同时校验 exchangeType，
     * 否则 SSE 与 SZSE 同代码标的会串用前收。
     */
    @Test
    void indexChangeDoesNotCrossMatchSameCodeDifferentExchange() {
        LocalDate d1 = LocalDate.of(2025, 1, 2); // 前一交易日
        LocalDate d2 = LocalDate.of(2025, 1, 3); // 信号日

        // 按 stockCode ASC, exchangeType ASC, tradeDate ASC 排序
        List<MarketData> bars = new ArrayList<>();
        // 000001 SSE：d1=100, d2=110（+10%）
        bars.add(bar("000001", "SSE", d1, 100.0));
        bars.add(bar("000001", "SSE", d2, 110.0));
        // 000001 SZSE：d2=10（仅有信号日数据，无前一交易日）
        // 排序后 000001|SSE 的 d2 bar 紧邻 000001|SZSE 的 d2 bar
        bars.add(bar("000001", "SZSE", d2, 10.0));

        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(bars);

        MarketEnvironmentAnalyzer.MarketIndicators mi = analyzer().analyze(d2, 60);

        // 修复后：SSE 有前日 bar（100→110），SZSE 无前日 bar 不参与 prevSum
        // todaySum=110+10=120, prevSum=100 → (120-100)/100*100 = 20%
        // 修复前：SZSE 串用 SSE 的 d2 close=110 作为前收 → prevSum=100+110=210 → (120-210)/210 ≈ -42.9%
        assertThat(mi.indexChange()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
    }

}
