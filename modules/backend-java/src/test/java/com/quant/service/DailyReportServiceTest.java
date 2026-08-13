package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.DailyReport;
import com.quant.entity.PredictionRun;
import com.quant.entity.PredictionSnapshotItem;
import com.quant.entity.SelectorResult;
import com.quant.entity.StockInfo;
import com.quant.repository.DailyReportRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.PredictionRunRepository;
import com.quant.repository.PredictionSnapshotItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    @Mock
    private PredictionRunRepository predictionRunRepository;
    @Mock
    private PredictionSnapshotItemRepository predictionSnapshotItemRepository;
    @Mock
    private DailyReportRepository dailyReportRepository;
    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private MarketPriceRawRepository marketPriceRawRepository;
    @Mock
    private LabelSnapshotRepository labelSnapshotRepository;
    @Mock
    private TradingCalendarService tradingCalendarService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DailyReportService service() {
        return new DailyReportService(
                predictionRunRepository,
                predictionSnapshotItemRepository,
                dailyReportRepository,
                selectorResultRepository,
                stockInfoRepository,
                marketPriceRawRepository,
                labelSnapshotRepository,
                tradingCalendarService,
                objectMapper);
    }

    private static PredictionRun publishedRun(String runId, LocalDate tradeDate, int horizon, String track,
                                              int publishVersion, int itemCount) {
        PredictionRun r = new PredictionRun();
        r.setRunId(runId);
        r.setTradeDate(tradeDate);
        r.setHorizon(horizon);
        r.setTrack(track);
        r.setPublishVersion(publishVersion);
        r.setStatus(PredictionRun.STATUS_PUBLISHED);
        r.setItemCount(itemCount);
        r.setFactorVersion("classic_v1");
        r.setModelVersion("m1");
        r.setStrategyType("CLASSIC_SELECTOR");
        return r;
    }

    private static PredictionSnapshotItem snapshotItem(String runId, int rank, String code, String exchange,
                                                       String name, double score) {
        PredictionSnapshotItem it = new PredictionSnapshotItem();
        it.setRunId(runId);
        it.setRankNo(rank);
        it.setStockCode(code);
        it.setExchangeType(exchange);
        it.setStockName(name);
        it.setScore(score);
        it.setScoreType("RULE_SCORE");
        it.setReasonsJson("{}");
        return it;
    }

    private static StockInfo stockInfo(String code, String exchange, String industry) {
        StockInfo s = new StockInfo();
        s.setStockCode(code);
        s.setExchangeType(exchange);
        s.setIndustry(industry);
        return s;
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate, int returned) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        r.setRunStatus("SUCCESS");
        r.setFactorVersion(strategy.toLowerCase() + "_v1");
        r.setTotalCandidates(100);
        r.setSuccessCandidates(returned);
        return r;
    }

    @SuppressWarnings("unchecked")
    @Test
    void dailyReportAbstainsWhenNoRun() {
        LocalDate tradeDate = LocalDate.of(2025, 1, 2);
        // 该日该轨道无任何预测快照 → abstain（NOT_PUBLISHED），绝不回退 selector
        when(predictionRunRepository.findByTradeDateAndHorizonAndTrackOrderByPublishVersionDesc(
                tradeDate, 3, PredictionRun.TRACK_RULE_SCORE))
                .thenReturn(List.of());

        Map<String, Object> out = service().buildDailyReport(
                tradeDate, 3, PredictionRun.TRACK_RULE_SCORE, null);

        assertThat(out.get("status")).isEqualTo("NOT_PUBLISHED");
        assertThat(out.get("trade_date")).isEqualTo("2025-01-02");
        assertThat(out.get("horizon")).isEqualTo(3);
        assertThat(out.get("track")).isEqualTo(PredictionRun.TRACK_RULE_SCORE);
        assertThat((List<Object>) out.get("items")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void dailyReportProjectsPublishedRun() {
        LocalDate tradeDate = LocalDate.of(2025, 1, 2);
        PredictionRun run = publishedRun("RUN1", tradeDate, 3, PredictionRun.TRACK_RULE_SCORE, 1, 2);

        when(predictionRunRepository.findByTradeDateAndHorizonAndTrackOrderByPublishVersionDesc(
                tradeDate, 3, PredictionRun.TRACK_RULE_SCORE))
                .thenReturn(List.of(run));
        when(predictionSnapshotItemRepository.findByRunIdOrderByRankNoAsc("RUN1"))
                .thenReturn(List.of(
                        snapshotItem("RUN1", 1, "000001", "SZSE", "测试A", 80.0),
                        snapshotItem("RUN1", 2, "000002", "SZSE", "测试B", 75.0)
                ));
        // 首次生成投影头：不存在既有行 → 新建并落库
        when(dailyReportRepository.findByTradeDateAndHorizonAndTrackAndPublishVersion(
                tradeDate, 3, PredictionRun.TRACK_RULE_SCORE, 1))
                .thenReturn(Optional.empty());
        // 行业：两只同属「银行」→ 100% 集中度触发告警
        when(stockInfoRepository.findByStockKeyIn(any())).thenReturn(List.of(
                stockInfo("000001", "SZSE", "银行"),
                stockInfo("000002", "SZSE", "银行")
        ));

        Map<String, Object> out = service().buildDailyReport(
                tradeDate, 3, PredictionRun.TRACK_RULE_SCORE, null);

        assertThat(out.get("status")).isEqualTo(PredictionRun.STATUS_PUBLISHED);
        assertThat(out.get("trade_date")).isEqualTo("2025-01-02");
        assertThat(out.get("prediction_run_id")).isEqualTo("RUN1");
        assertThat(out.get("publish_version")).isEqualTo(1);
        assertThat(out.get("report_version")).isEqualTo(1);

        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("stock_code")).isEqualTo("000001");
        assertThat(items.get(0).get("rank_no")).isEqualTo(1);

        // 行业集中度：两只全属「银行」→ 100%，应触发告警
        Map<String, Object> conc = (Map<String, Object>) out.get("industry_concentration");
        assertThat(conc.get("total")).isEqualTo(2);
        assertThat(conc.get("max_concentration_pct")).isEqualTo(100.0);
        assertThat((String) conc.get("warning")).contains("银行");
    }

    @Test
    void dailyReportReusesExistingProjection() {
        LocalDate tradeDate = LocalDate.of(2025, 1, 2);
        PredictionRun run = publishedRun("RUN1", tradeDate, 3, PredictionRun.TRACK_PROBABILITY, 1, 1);

        when(predictionRunRepository.findByTradeDateAndHorizonAndTrackOrderByPublishVersionDesc(
                tradeDate, 3, PredictionRun.TRACK_PROBABILITY))
                .thenReturn(List.of(run));
        when(predictionSnapshotItemRepository.findByRunIdOrderByRankNoAsc("RUN1"))
                .thenReturn(List.of(snapshotItem("RUN1", 1, "000001", "SZSE", "A", 80.0)));
        // 投影头已存在 → 复用，不新建
        DailyReport existing = new DailyReport();
        existing.setReportId("RPT_EXISTING");
        existing.setReportVersion(1);
        existing.setContentHash(existingHashFor("RUN1")); // 与候选正文一致的 hash
        when(dailyReportRepository.findByTradeDateAndHorizonAndTrackAndPublishVersion(
                tradeDate, 3, PredictionRun.TRACK_PROBABILITY, 1))
                .thenReturn(Optional.of(existing));

        Map<String, Object> out = service().buildDailyReport(
                tradeDate, 3, PredictionRun.TRACK_PROBABILITY, null);

        assertThat(out.get("status")).isEqualTo(PredictionRun.STATUS_PUBLISHED);
        assertThat(out.get("report_id")).isEqualTo("RPT_EXISTING");
    }

    /**
     * 用 service 同款确定性 hash 算法计算候选正文 hash，避免 hardcode 失配导致 fail-closed。
     */
    private String existingHashFor(String runId) {
        // 复刻 DailyReportService.computeContentHash 的拼接规则（不依赖私有方法）
        PredictionSnapshotItem it = snapshotItem(runId, 1, "000001", "SZSE", "A", 80.0);
        String line = (it.getRankNo() != null ? it.getRankNo() : 0) + "|" + it.getStockCode()
                + "|" + it.getExchangeType() + "|" + nz(it.getStockName()) + "|" + it.getScore()
                + "|" + nz(it.getScoreType()) + "|" + nz(it.getReasonsJson());
        return com.quant.research.artifact.ArtifactManifestValidator.sha256Hex(line);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ── 多日信号趋势（独立的 selector 视角视图，签名未变）──────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void trendReportAggregatesDailyCandidateCounts() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(
                        result("R2", "CLASSIC_SELECTOR", d2, 5),
                        result("R1", "CLASSIC_SELECTOR", d1, 3)
                ));

        Map<String, Object> out = service().buildTrendReport(d1, d2);

        assertThat(out.get("signal_days")).isEqualTo(2);
        assertThat((List<String>) out.get("strategies")).containsExactly("CLASSIC_SELECTOR");

        List<Map<String, Object>> trend = (List<Map<String, Object>>) out.get("daily_trend");
        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).get("trade_date")).isEqualTo("2025-01-02");
        assertThat(trend.get(0).get("total_candidates")).isEqualTo(3);
        assertThat(trend.get(1).get("trade_date")).isEqualTo("2025-01-03");
        assertThat(trend.get(1).get("total_candidates")).isEqualTo(5);
    }

    @Test
    void trendReportReturnsEmptyWhenNoData() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().buildTrendReport(d, d);
        assertThat(out.get("signal_days")).isEqualTo(0);
        assertThat((List<?>) out.get("daily_trend")).isEmpty();
    }
}
