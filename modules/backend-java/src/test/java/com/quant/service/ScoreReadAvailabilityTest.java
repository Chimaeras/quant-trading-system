package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.StockMultiDimScore;
import com.quant.entity.StockTrendScore;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.StockMultiDimScoreRepository;
import com.quant.repository.StockTrendScoreRepository;
import com.quant.service.scoring.EnhancedMarketSentimentService;
import com.quant.service.scoring.MarketEnvironmentAnalyzer;
import com.quant.service.scoring.ScoreReadAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R3-02：打分读取接口必须显式标注可用性。
 *
 * <p>修复前这两个接口把旧数据与空数据**直接返回**，调用方无从区分
 * 「这是最新结果」「这是半年前的结果」与「根本没有结果」：
 * <pre>
 *   trend     表 3345 行，最新只到 2026-04-07 → GET 以 trade_date=2026-04-07 交付
 *   multi_dim 表 0 行                        → GET 返回 trade_date=""、total=0
 * </pre>
 */
class ScoreReadAvailabilityTest {

    private static final LocalDate MARKET_LATEST = LocalDate.of(2026, 10, 13);
    private static final LocalDate HALF_YEAR_AGO = LocalDate.of(2026, 4, 7);

    // ---------------- 汇总逻辑（纯函数） ----------------

    @Test
    void noRowsAtAllIsEmptyNotBlankResult() {
        Map<String, Object> out = ScoreReadAvailability.describe(null, null, MARKET_LATEST);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_EMPTY);
        assertThat(out.get("effective_trade_date")).isEqualTo("");
        assertThat(out.get("expected_trade_date")).isEqualTo(MARKET_LATEST.toString());
        assertThat(out.get("staleness_days")).isNull();
        assertThat(out.get("downgraded")).isEqualTo(false);
    }

    @Test
    void halfYearOldResultIsStaleNotLatest() {
        Map<String, Object> out = ScoreReadAvailability.describe(HALF_YEAR_AGO, null, MARKET_LATEST);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_STALE);
        assertThat(out.get("effective_trade_date")).isEqualTo("2026-04-07");
        assertThat(out.get("staleness_days")).isEqualTo(189L);
        assertThat(out.get("downgraded")).isEqualTo(false);
    }

    @Test
    void upToDateResultIsOk() {
        Map<String, Object> out = ScoreReadAvailability.describe(MARKET_LATEST, null, MARKET_LATEST);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_OK);
        assertThat(out.get("staleness_days")).isEqualTo(0L);
    }

    @Test
    void requestedDateSilentlyDowngradedIsFlaggedEvenWhenNotOld() {
        // 调用方要 10-13，实际只取到 10-12：绝对日期不算旧，但被静默降级了
        LocalDate requested = MARKET_LATEST;
        LocalDate effective = MARKET_LATEST.minusDays(1);

        Map<String, Object> out = ScoreReadAvailability.describe(effective, requested, effective);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_STALE);
        assertThat(out.get("downgraded")).isEqualTo(true);
        assertThat(out.get("requested_trade_date")).isEqualTo(requested.toString());
        assertThat(out.get("effective_trade_date")).isEqualTo(effective.toString());
    }

    @Test
    void matchingRequestedDateIsNotDowngraded() {
        Map<String, Object> out = ScoreReadAvailability.describe(HALF_YEAR_AGO, HALF_YEAR_AGO, null);

        assertThat(out.get("downgraded")).isEqualTo(false);
        // 行情参照不可知时不擅自判定陈旧，但请求日完全命中
        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_OK);
        assertThat(out.get("staleness_days")).isEqualTo(0L);
    }

    @Test
    void unknownMarketReferenceDoesNotFabricateStaleness() {
        Map<String, Object> out = ScoreReadAvailability.describe(HALF_YEAR_AGO, null, null);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_OK);
        assertThat(out.get("expected_trade_date")).isEqualTo("");
        assertThat(out.get("as_of")).isEqualTo(LocalDate.now().toString());
    }

    // ---------------- 接口接线（趋势打分） ----------------

    private StockTrendScoringService trendService(MarketDataRepository marketRepo,
                                                  StockTrendScoreRepository scoreRepo) {
        return new StockTrendScoringService(
                marketRepo,
                mock(StockInfoRepository.class),
                mock(PointInTimeUniverseProvider.class),
                scoreRepo,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void trendListScoresMarksHalfYearOldDataStale() {
        MarketDataRepository marketRepo = mock(MarketDataRepository.class);
        StockTrendScoreRepository scoreRepo = mock(StockTrendScoreRepository.class);
        when(marketRepo.findMaxTradeDate()).thenReturn(Optional.of(MARKET_LATEST));
        when(scoreRepo.findMaxTradeDateByScoreType(any())).thenReturn(Optional.of(HALF_YEAR_AGO));
        when(scoreRepo.findByTradeDateAndScoreTypeOrderByTotalScoreDesc(any(), any(), any()))
                .thenReturn(Page.<StockTrendScore>empty());

        Map<String, Object> out = trendService(marketRepo, scoreRepo).listScores(null, 0, 20);

        assertThat(out.get("trade_date")).isEqualTo("2026-04-07");
        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_STALE);
        assertThat(out.get("expected_trade_date")).isEqualTo(MARKET_LATEST.toString());
        assertThat(out.get("staleness_days")).isEqualTo(189L);
    }

    @Test
    void trendListScoresMarksNoRowsAsEmpty() {
        MarketDataRepository marketRepo = mock(MarketDataRepository.class);
        StockTrendScoreRepository scoreRepo = mock(StockTrendScoreRepository.class);
        when(marketRepo.findMaxTradeDate()).thenReturn(Optional.of(MARKET_LATEST));
        when(scoreRepo.findMaxTradeDateByScoreType(any())).thenReturn(Optional.empty());

        Map<String, Object> out = trendService(marketRepo, scoreRepo).listScores(null, 0, 20);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_EMPTY);
        assertThat(out.get("effective_trade_date")).isEqualTo("");
        assertThat(out.get("total")).isEqualTo(0L);
        assertThat(out.get("score_type")).isEqualTo(StockTrendScore.SCORE_TYPE_TREND);
    }

    @Test
    void trendListScoresMarksFreshDataOk() {
        MarketDataRepository marketRepo = mock(MarketDataRepository.class);
        StockTrendScoreRepository scoreRepo = mock(StockTrendScoreRepository.class);
        when(marketRepo.findMaxTradeDate()).thenReturn(Optional.of(MARKET_LATEST));
        when(scoreRepo.findMaxTradeDateByScoreType(any())).thenReturn(Optional.of(MARKET_LATEST));
        when(scoreRepo.findByTradeDateAndScoreTypeOrderByTotalScoreDesc(any(), any(), any()))
                .thenReturn(Page.<StockTrendScore>empty());

        Map<String, Object> out = trendService(marketRepo, scoreRepo).listScores(null, 0, 20);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_OK);
    }

    // ---------------- 接口接线（多维打分） ----------------

    @Test
    void multiDimListScoresMarksNoRowsAsEmptyNotBlankTradeDate() {
        MarketDataRepository marketRepo = mock(MarketDataRepository.class);
        StockMultiDimScoreRepository scoreRepo = mock(StockMultiDimScoreRepository.class);
        when(marketRepo.findMaxTradeDate()).thenReturn(Optional.of(MARKET_LATEST));
        when(scoreRepo.findMaxTradeDate(any())).thenReturn(Optional.empty());

        MultiDimScoringService service = new MultiDimScoringService(
                marketRepo,
                mock(PointInTimeUniverseProvider.class),
                scoreRepo,
                mock(ObjectMapper.class),
                mock(EnhancedMarketSentimentService.class),
                mock(MarketEnvironmentAnalyzer.class));

        Map<String, Object> out = service.listScores(null, 0, 20);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_EMPTY);
        assertThat(out.get("effective_trade_date")).isEqualTo("");
        assertThat(out.get("expected_trade_date")).isEqualTo(MARKET_LATEST.toString());
        assertThat(out.get("factor_version")).isEqualTo(StockMultiDimScore.FACTOR_VERSION);
    }

    @Test
    void multiDimListScoresMarksStaleRows() {
        MarketDataRepository marketRepo = mock(MarketDataRepository.class);
        StockMultiDimScoreRepository scoreRepo = mock(StockMultiDimScoreRepository.class);
        when(marketRepo.findMaxTradeDate()).thenReturn(Optional.of(MARKET_LATEST));
        when(scoreRepo.findMaxTradeDate(any())).thenReturn(Optional.of(HALF_YEAR_AGO));
        when(scoreRepo.findByTradeDateAndFactorVersionOrderByCompositeScoreDesc(any(), any(), any()))
                .thenReturn(Page.<StockMultiDimScore>empty());

        MultiDimScoringService service = new MultiDimScoringService(
                marketRepo,
                mock(PointInTimeUniverseProvider.class),
                scoreRepo,
                mock(ObjectMapper.class),
                mock(EnhancedMarketSentimentService.class),
                mock(MarketEnvironmentAnalyzer.class));

        Map<String, Object> out = service.listScores(null, 0, 20);

        assertThat(out.get("data_status")).isEqualTo(ScoreReadAvailability.STATUS_STALE);
        assertThat(out.get("staleness_days")).isEqualTo(189L);
        assertThat(out.get("list")).isEqualTo(List.of());
    }
}
