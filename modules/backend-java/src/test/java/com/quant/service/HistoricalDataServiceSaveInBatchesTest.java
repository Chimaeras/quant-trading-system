package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.AdjustFactorRepository;
import com.quant.research.AdjustFactorDerivationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * HistoricalDataService.saveInBatches 的统一入库质量闸测试（P1-4）。
 *
 * <p>单股增量路径（updateHistoryInRange / updateHistoryInRangeStockInfoStyle）此前只按 existingDates
 * 过滤入库，未显式调用 isValidDailyBar；此处集中到 saveInBatches 入口统一把关，
 * 确保异常 OHLC（high&lt;low、open/close 越界、负量额）不写入 market_data。
 */
@ExtendWith(MockitoExtension.class)
class HistoricalDataServiceSaveInBatchesTest {

    @Mock
    private MarketDataRepository marketDataRepo;
    @Mock
    private MarketPriceRawRepository marketPriceRawRepo;
    @Mock
    private AdjustFactorRepository adjustFactorRepo;
    @Mock
    private AdjustFactorDerivationService adjustFactorDerivationService;

    @InjectMocks
    private HistoricalDataService service;

    @Test
    void saveInBatchesDropsInvalidBarsBeforePersisting() throws Exception {
        MarketData valid = bar("10.10", "10.50", "9.90", "10.30", 1000L);
        MarketData invalidHighBelowLow = bar("10.10", "9.90", "10.00", "10.30", 1000L); // high<low

        invokeSaveInBatches(List.of(valid, invalidHighBelowLow));

        verify(marketDataRepo).saveAll(List.of(valid));
    }

    @Test
    void saveInBatchesSkipsEntirelyWhenAllInvalid() throws Exception {
        MarketData invalidNegativeVolume = bar("10.10", "10.50", "9.90", "10.30", -5L);

        invokeSaveInBatches(List.of(invalidNegativeVolume));

        verify(marketDataRepo, never()).saveAll(anyList());
    }

    private void invokeSaveInBatches(List<MarketData> rows) throws Exception {
        Method m = HistoricalDataService.class.getDeclaredMethod("saveInBatches", List.class);
        m.setAccessible(true);
        m.invoke(service, rows);
    }

    private static MarketData bar(String open, String high, String low, String close, Long volume) {
        MarketData bar = new MarketData();
        bar.setTradeDate(LocalDate.of(2026, 6, 30));
        bar.setOpenPrice(new BigDecimal(open));
        bar.setHighPrice(new BigDecimal(high));
        bar.setLowPrice(new BigDecimal(low));
        bar.setClosePrice(new BigDecimal(close));
        bar.setVolume(volume);
        bar.setAmount(new BigDecimal("10300"));
        return bar;
    }
}
