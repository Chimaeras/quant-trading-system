package com.quant.service;

import com.quant.entity.IndexQuote;
import com.quant.repository.IndexQuoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QP-005 指数行情：解析 + 幂等持久化 + 覆盖率统计测试。
 */
@ExtendWith(MockitoExtension.class)
class IndexDataServiceTest {

    @Mock
    private IndexQuoteRepository indexQuoteRepository;

    @InjectMocks
    private IndexDataService service;

    private String sampleIndexJson() {
        return """
                {"data":{"klines":[
                  "2025-01-02,3800,3820,3830,3790,100000,300000000,1.0,0.50,19,0.6",
                  "2025-01-03,3820,3810,3835,3805,95000,290000000,0.8,-0.26,-10,0.5",
                  "2025-01-06,3810,3835,3845,3800,110000,320000000,1.2,0.66,25,0.7"
                ]},"rc":0}
                """;
    }

    @Test
    void parseIndexKlinesExtractsOhlcv() throws Exception {
        List<IndexQuote> quotes = service.parseIndexKlines("000300", "沪深300", sampleIndexJson());

        assertThat(quotes).hasSize(3);
        IndexQuote first = quotes.get(0);
        assertThat(first.getIndexCode()).isEqualTo("000300");
        assertThat(first.getIndexName()).isEqualTo("沪深300");
        assertThat(first.getTradeDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(first.getOpenPrice().doubleValue()).isEqualTo(3800.0);
        assertThat(first.getClosePrice().doubleValue()).isEqualTo(3820.0);
        assertThat(first.getHighPrice().doubleValue()).isEqualTo(3830.0);
        assertThat(first.getLowPrice().doubleValue()).isEqualTo(3790.0);
        assertThat(first.getVolume()).isEqualTo(100000L);
        assertThat(first.getChangePct().doubleValue()).isEqualTo(0.50);
        assertThat(first.getSource()).isEqualTo("eastmoney");
        assertThat(first.getReliability()).isEqualTo("limited");
    }

    @Test
    void parseIndexKlinesHandlesEmptyData() throws Exception {
        assertThat(service.parseIndexKlines("000300", "沪深300", "{\"data\":{}}")).isEmpty();
        assertThat(service.parseIndexKlines("000300", "沪深300", "{}")).isEmpty();
        assertThat(service.parseIndexKlines("000300", "沪深300", "{\"data\":{\"klines\":[]}}")).isEmpty();
    }

    @Test
    void parseIndexKlinesSkipsMalformedLines() throws Exception {
        String json = "{\"data\":{\"klines\":[\"2025-01-02,1,2\", \"bad,1\", \"\"]}}";
        List<IndexQuote> quotes = service.parseIndexKlines("000300", "沪深300", json);
        // 第一行 parts.length=3 < 7 跳过，第二行 parts.length=2 < 7 跳过，第三行空跳过
        assertThat(quotes).isEmpty();
    }

    @Test
    void parseIndexKlinesSkipsUnparseableDate() throws Exception {
        String json = "{\"data\":{\"klines\":[\"bad-date,1,2,3,4,5,6,7,8,9,10\"]}}";
        assertThat(service.parseIndexKlines("000300", "沪深300", json)).isEmpty();
    }

    @Test
    void buildCoverageReportsPerIndexStats() {
        when(indexQuoteRepository.count()).thenReturn(3000L);
        when(indexQuoteRepository.countDistinctIndices()).thenReturn(4L);
        when(indexQuoteRepository.countByIndex("000300")).thenReturn(1000L);
        when(indexQuoteRepository.countByIndex("000905")).thenReturn(800L);
        when(indexQuoteRepository.countByIndex("000852")).thenReturn(700L);
        when(indexQuoteRepository.countByIndex("000001")).thenReturn(500L);
        when(indexQuoteRepository.findMinTradeDateByIndex("000300")).thenReturn(Optional.of(LocalDate.of(2023, 1, 3)));
        when(indexQuoteRepository.findMaxTradeDateByIndex("000300")).thenReturn(Optional.of(LocalDate.of(2025, 7, 31)));
        when(indexQuoteRepository.findMinTradeDateByIndex("000905")).thenReturn(Optional.empty());
        when(indexQuoteRepository.findMaxTradeDateByIndex("000905")).thenReturn(Optional.empty());
        when(indexQuoteRepository.findMinTradeDateByIndex("000852")).thenReturn(Optional.empty());
        when(indexQuoteRepository.findMaxTradeDateByIndex("000852")).thenReturn(Optional.empty());
        when(indexQuoteRepository.findMinTradeDateByIndex("000001")).thenReturn(Optional.empty());
        when(indexQuoteRepository.findMaxTradeDateByIndex("000001")).thenReturn(Optional.empty());
        when(indexQuoteRepository.countBySource(IndexDataService.SOURCE_EASTMONEY)).thenReturn(3000L);
        when(indexQuoteRepository.countBySource(IndexDataService.SOURCE_TUSHARE)).thenReturn(0L);
        when(indexQuoteRepository.countByReliability(IndexDataService.RELIABILITY_LIMITED)).thenReturn(3000L);
        when(indexQuoteRepository.countByReliability(IndexDataService.RELIABILITY_OFFICIAL)).thenReturn(0L);

        Map<String, Object> cov = service.buildCoverage();

        assertThat(cov.get("total_rows")).isEqualTo(3000L);
        assertThat(cov.get("distinct_indices")).isEqualTo(4L);
        assertThat(cov.get("status")).asString().contains("已启用");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indices = (List<Map<String, Object>>) cov.get("indices");
        assertThat(indices).hasSize(4);
        assertThat(indices.get(0).get("index_code")).isEqualTo("000300");
        assertThat(indices.get(0).get("rows")).isEqualTo(1000L);
        assertThat(indices.get(0).get("earliest_date")).isEqualTo("2023-01-03");
    }
}
