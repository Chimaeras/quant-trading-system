package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.T1Candidate;
import com.quant.repository.IndexQuoteRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.ModelVersionRepository;
import com.quant.repository.T1CandidateRepository;
import com.quant.service.scoring.LimitUpEcologyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.temporal.ChronoUnit;
import org.mockito.ArgumentCaptor;

/**
 * T+1 候选核心闭环：候选生成评分排序 + 次日核销命中率。
 */
@ExtendWith(MockitoExtension.class)
class T1CandidateServiceTest {

    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private PointInTimeUniverseProvider pointInTimeUniverseProvider;
    @Mock
    private T1CandidateRepository t1CandidateRepository;
    @Mock
    private LimitUpEcologyService limitUpEcologyService;
    @Mock
    private TradingCalendarService tradingCalendarService;
    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private IndexQuoteRepository indexQuoteRepository;
    @Mock
    private com.quant.research.CalibratorService calibratorService;

    private T1CandidateService service() {
        ObjectMapper om = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build();
        return new T1CandidateService(marketDataRepository, pointInTimeUniverseProvider, t1CandidateRepository,
                limitUpEcologyService, tradingCalendarService, modelVersionRepository, indexQuoteRepository, om, calibratorService);
    }

    /**
     * P0-3 默认夹具：把日历设定为「覆盖 2024-01-01..2026-08-19 且每日均为交易日」。
     * <p>上界刻意取 2026-08-19，与本地实测的日历末日一致——这样「日历不覆盖」
     * 这一分支才在夹具中真实可达，而不是靠窗口恰好包住测试日期而掩盖。
     * <p>用 {@code lenient()} 是因为并非所有用例都会走到日历查询（例如核销类用例），
     * Mockito 严格模式会把未使用的 stub 判为失败。
     * 需要「非交易日」或「区间外」语义的用例可自行覆盖该 stub。
     */
    @org.junit.jupiter.api.BeforeEach
    void stubCalendarAsCoveringAndTrading() {
        org.mockito.Mockito.lenient().when(tradingCalendarService.isTradingDay(any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{LocalDate.of(2024, 1, 1), LocalDate.of(2026, 8, 19)});
    }

    private void stubUniverse(LocalDate d, StockInfo info) {
        Map<String, StockInfo> byKey = new HashMap<>();
        byKey.put(info.getStockCode() + "|" + info.getExchangeType(), info);
        when(pointInTimeUniverseProvider.loadAsOf(d)).thenReturn(
                new PointInTimeUniverseProvider.UniverseView(
                        d,
                        List.of(info),
                        byKey,
                        PointInTimeUniverseProvider.SOURCE_PIT_HISTORY,
                        PointInTimeUniverseProvider.RELIABILITY_OFFICIAL,
                        false,
                        1));
    }

    private static com.quant.entity.ModelVersion championModel(int max) {
        var mv = new com.quant.entity.ModelVersion();
        mv.setModelVersion("logistic_v1_t1");
        mv.setAlgorithm(com.quant.research.LogisticModelTrainer.ALGORITHM);
        mv.setStrategyType("T1");
        mv.setHorizon(1);
        mv.setStage("champion");
        mv.setFeatureSetVersion("t1_rule_score_v2");
        mv.setSchemaHash(com.quant.research.LogisticModelTrainer.ruleScoreSchemaHash());
        mv.setOutputKind(com.quant.research.LogisticModelTrainer.OUTPUT_KIND);
        mv.setLabelVersion("label_v1");
        mv.setDataSnapshotId("snap_1");
        mv.setCodeVersion("model_test_v1");
        mv.setCreatedAt(LocalDate.of(2024, 12, 27).atStartOfDay());
        mv.setDeployKey(com.quant.research.artifact.ModelImportService.buildDeployKey(mv.getAlgorithm(), mv.getFeatureSetVersion(), mv.getSchemaHash()));
        mv.setArtifact("{\"weights\":[1.0],\"bias\":0.0,\"features\":[\"rule_score\"],"
                + "\"preprocessor\":{\"type\":\"min_max\",\"fit_window\":\"train_only\",\"min\":[0.0],\"max\":[" + max + "]}}");
        mv.setArtifactHash(com.quant.research.artifact.ArtifactManifestValidator.sha256Hex(mv.getArtifact()));
        return mv;
    }

    private static com.quant.entity.Calibrator championCalibrator(com.quant.entity.ModelVersion mv) {
        var cal = new com.quant.entity.Calibrator();
        cal.setCalibratorVersion("cal_t1_test");
        cal.setStrategyType("T1");
        cal.setHorizon(1);
        cal.setStage("champion");
        cal.setBaseModelVersion(mv.getModelVersion());
        cal.setBaseOutputKind(mv.getOutputKind());
        cal.setLabelVersion(mv.getLabelVersion());
        cal.setDataSnapshotId(mv.getDataSnapshotId());
        cal.setCodeVersion("cal_test_v1");
        cal.setFitEnd(LocalDate.of(2024, 10, 31));
        cal.setEvalStart(LocalDate.of(2024, 11, 1));
        cal.setEvalEnd(LocalDate.of(2024, 12, 20));
        cal.setCreatedAt(LocalDate.of(2024, 12, 27).atStartOfDay());
        cal.setInputDomain("rule_score");
        cal.setOutputKind("platt_sigmoid");
        cal.setCalibratorJson("{\"kind\":\"platt_sigmoid\",\"input_domain\":\"rule_score\",\"norm\":{\"min\":0,\"max\":50},\"sigmoid\":{\"a\":1,\"b\":0}}");
        cal.setCalibratorHash(com.quant.research.artifact.ArtifactManifestValidator.sha256Hex(cal.getCalibratorJson()));
        return cal;
    }

    @Test
    void generateCandidatesScoresAndRanksTopN() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        List<MarketData> rangeBars = strongTrendBars("000001", "SZSE", d, 35);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d)).thenReturn(List.of(rangeBars.get(rangeBars.size() - 1)));
        // D-T1-06：覆盖率分母只计「回看窗口内真实有行情」的键
        stubActivePairs("000001", "SZSE");
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        info.setListDate(d.minusDays(500));
        info.setStatus(1);
        stubUniverse(d, info);
        // D-T1-01：候选池在打分前已用 excludeLimitUpAtClose=true 排除收盘涨停的标的，
        // 故池内标的的 is_limit_up 必须为 false —— 旧的 +30 涨停 / +15 连板 加分项
        // 在这条路径上恒不可达，已从 scoreT1 中移除。此处按真实不变式提供特征。
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any()))
                .thenReturn(Map.of("is_limit_up", false, "lianban_days", 0,
                        "volume_ratio", 6.0, "turnover_rate", 5.0, "change_pct", 3.7));
        when(limitUpEcologyService.ecology(any(), anyInt())).thenReturn(Map.of("limit_up_count", 1));
        when(limitUpEcologyService.historicalNextDayPremium(any(), anyInt()))
                .thenReturn(Map.of("avg_gap", 0.01, "high_open_rate", 0.6));
        when(modelVersionRepository.findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        Map<String, Object> out = service().generateCandidates(d, 5);

        assertThat(out.get("status")).isEqualTo("OK");
        assertThat(out.get("top_n")).isEqualTo(1); // 仅 1 只可交易标的
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) out.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("stock_code")).isEqualTo("000001");
        assertThat(candidates.get(0).get("score_type")).isEqualTo("RULE_SCORE");
        // rule_score 必须真实越过入选阈值，且值域收敛到 0–50（满分 50），不再是 0–100
        // D-T1-07（2026-10-14）：阈值由 35 重标定为 30（见 application.yml 标定注释）
        assertThat((double) candidates.get(0).get("rule_score")).isGreaterThanOrEqualTo(30.0);
        assertThat((double) candidates.get(0).get("score")).isLessThanOrEqualTo(50.0);
        assertThat(out.get("rule_score_max")).isEqualTo(50.0);
        assertThat(out.get("threshold_version")).isEqualTo("t1_rule_v3");
        // 非涨停标的的可买性预判为「可买」（涨停才是「高开追高谨慎」）
        assertThat(candidates.get(0).get("buyability")).isEqualTo("待盘中核实");
    }

    /**
     * D-T1-01 回归闸：收盘涨停的标的必须在打分前被预过滤，不得进入候选。
     * 若未来有人把涨停/连板加分项加回 scoreT1，本测试会先失败。
     */
    @Test
    void generateCandidatesExcludesLimitUpAtCloseBeforeScoring() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        List<MarketData> rangeBars = rangeBars("000001", "SZSE", d, 35);
        // 把最后一天做成收盘涨停（+10%）：收盘价 = 前收 × 1.10
        MarketData last = rangeBars.get(rangeBars.size() - 1);
        double prevClose = 13.3;
        last.setPreClose(BigDecimal.valueOf(prevClose));
        last.setClosePrice(BigDecimal.valueOf(Math.round(prevClose * 1.10 * 100.0) / 100.0));
        last.setHighPrice(last.getClosePrice().add(BigDecimal.valueOf(0.2)));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d)).thenReturn(List.of(last));
        // D-T1-06：覆盖率分母只计「回看窗口内真实有行情」的键
        stubActivePairs("000001", "SZSE");
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        info.setListDate(d.minusDays(500));
        info.setStatus(1);
        stubUniverse(d, info);
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        Map<String, Object> out = service().generateCandidates(d, 5);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) out.get("candidates");
        assertThat(candidates).isEmpty();
        assertThat(out.get("total_scored")).isEqualTo(0);
        assertThat(out.get("status")).isEqualTo("NO_ELIGIBLE_CANDIDATE");
    }

    @Test
    void generateCandidatesUsesProbabilityWhenT1ModelAvailable() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        List<MarketData> rangeBars = strongTrendBars("000001", "SZSE", d, 35);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d)).thenReturn(List.of(rangeBars.get(rangeBars.size() - 1)));
        // D-T1-06：覆盖率分母只计「回看窗口内真实有行情」的键
        stubActivePairs("000001", "SZSE");
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        info.setListDate(d.minusDays(500));
        info.setStatus(1);
        stubUniverse(d, info);
        // 信号足够强（量比 3.5 + 动量 + 趋势）→ rule_score ≥ 35 通过入选门禁；
        // 标的本身非涨停（涨停标的已在打分前被排除，见 D-T1-01）。
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any()))
                .thenReturn(Map.of("is_limit_up", false, "lianban_days", 0,
                        "volume_ratio", 6.0, "turnover_rate", 8.0, "change_pct", 3.0));
        when(limitUpEcologyService.ecology(any(), anyInt())).thenReturn(Map.of("limit_up_count", 0));
        when(limitUpEcologyService.historicalNextDayPremium(any(), anyInt()))
                .thenReturn(Map.of("avg_gap", 0.01, "high_open_rate", 0.5));
        // horizon=1 模型存在：artifact 含权重 1.0 / bias 0 / min 0 / max 50
        // （D-T1-01 之后 rule_score 值域为 0–50，preprocessor.max 必须同标度，
        //   否则会被 PREPROCESSOR_SCALE_MISMATCH 守卫拒绝推理）
        com.quant.entity.ModelVersion mv = championModel(50);
        com.quant.entity.Calibrator cal = championCalibrator(mv);
        when(calibratorService.loadChampion("T1", 1, mv.getModelVersion())).thenReturn(Optional.of(cal));
        when(calibratorService.apply(org.mockito.ArgumentMatchers.eq(cal), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(0.7);
        when(modelVersionRepository.findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.of(mv));
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        Map<String, Object> out = service().generateCandidates(d, 5);

        assertThat(out.get("score_type")).isEqualTo("CALIBRATED_PROBABILITY");
        assertThat(out.get("score_model")).isEqualTo("logistic_v1_t1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) out.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("score_type")).isEqualTo("CALIBRATED_PROBABILITY");
        assertThat(candidates.get(0).get("is_probability")).isEqualTo(true);
        // rule_score ∈ (0,50] 经 train-only min-max(0–50) 归一化后过 sigmoid → 概率 ∈ (0,1)，且保留 rule_score 字段
        assertThat((double) candidates.get(0).get("score")).isBetween(0.0, 1.0);
        assertThat(candidates.get(0).get("rule_score")).isNotNull();
    }

    /**
     * D-T1-01 标度守卫：按旧 0–100 标度训练的 champion 模型必须被拒绝推理。
     * 否则 min-max 归一化会把当前 0–50 的分数压到实际区间的一半，输出**被静默压低的伪概率**。
     */
    @Test
    void generateCandidatesRejectsModelTrainedOnOldZeroToHundredScale() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        List<MarketData> rangeBars = strongTrendBars("000001", "SZSE", d, 35);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d)).thenReturn(List.of(rangeBars.get(rangeBars.size() - 1)));
        // D-T1-06：覆盖率分母只计「回看窗口内真实有行情」的键
        stubActivePairs("000001", "SZSE");
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        info.setListDate(d.minusDays(500));
        info.setStatus(1);
        stubUniverse(d, info);
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any()))
                .thenReturn(Map.of("is_limit_up", false, "lianban_days", 0,
                        "volume_ratio", 6.0, "turnover_rate", 8.0, "change_pct", 3.0));
        when(limitUpEcologyService.ecology(any(), anyInt())).thenReturn(Map.of("limit_up_count", 0));
        when(limitUpEcologyService.historicalNextDayPremium(any(), anyInt()))
                .thenReturn(Map.of("avg_gap", 0.01, "high_open_rate", 0.5));
        // preprocessor.max=100 是旧标度（当前满分 50）→ 必须被守卫拒绝
        com.quant.entity.ModelVersion mv = championModel(100);
        when(modelVersionRepository.findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.of(mv));
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        Map<String, Object> out = service().generateCandidates(d, 5);

        // 拒绝推理 → 回退规则分，并在 fallback_reason 说明原因
        assertThat(out.get("score_type")).isEqualTo("RULE_SCORE");
        assertThat(String.valueOf(out.get("fallback_reason"))).contains("PREPROCESSOR_SCALE_MISMATCH");
    }

    /**
     * 回看窗口必须足够宽：30 个交易日至少需要 42 个自然日（含节假日更久）。
     * 若窗口仅 40 自然日（≈28 交易日），生产环境会因 stockBars.size() &lt; 30 静默跳过大部分标的。
     */
    @Test
    void generateCandidatesUsesLookbackWideEnoughForThirtyTradingDays() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        List<MarketData> rangeBars = rangeBars("000001", "SZSE", d, 35);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d)).thenReturn(List.of(rangeBars.get(rangeBars.size() - 1)));
        // D-T1-06：覆盖率分母只计「回看窗口内真实有行情」的键
        stubActivePairs("000001", "SZSE");
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        info.setListDate(d.minusDays(500));
        info.setStatus(1);
        stubUniverse(d, info);
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any()))
                .thenReturn(Map.of("is_limit_up", false, "lianban_days", 0,
                        "volume_ratio", 1.0, "turnover_rate", 2.0, "change_pct", 1.0));
        when(limitUpEcologyService.ecology(any(), anyInt())).thenReturn(Map.of("limit_up_count", 0));
        when(limitUpEcologyService.historicalNextDayPremium(any(), anyInt()))
                .thenReturn(Map.of("avg_gap", 0.0, "high_open_rate", 0.5));
        when(modelVersionRepository.findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        service().generateCandidates(d, 5);

        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(marketDataRepository)
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(startCaptor.capture(), any());
        long calendarDays = ChronoUnit.DAYS.between(startCaptor.getValue(), d);
        // 30 交易日至少需 42 自然日；窗口须 ≥ 42 才能保证不全量跳过
        assertThat(calendarDays).isGreaterThanOrEqualTo(42);
    }

    @Test
    void settleCandidatesComputesHitRate() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        LocalDate d1 = LocalDate.of(2025, 1, 3);
        // 候选 JSON（1 只，T 日收盘 10）
        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(d);
        rec.setTopN(1);
        rec.setCandidatesJson("[{\"stock_code\":\"000001\",\"exchange_type\":\"SZSE\",\"stock_name\":\"测试\","
                + "\"score\":70.0,\"close\":10.0,\"is_limit_up\":false,\"lianban_days\":0}]");
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.of(rec));
        when(tradingCalendarService.findTradingDays(d, d.plusDays(7)))
                .thenReturn(List.of(d, d1));
        // 次日收盘 11 → +10% → hit
        when(marketDataRepository.findByTradeDate(d1))
                .thenReturn(List.of(nextBar(d1, "000001", "SZSE", 11.0, 10.0)));

        Map<String, Object> out = service().settleCandidates(d);

        assertThat(out.get("next_trade_date")).isEqualTo(d1.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(((Number) summary.get("hit_count")).intValue()).isEqualTo(1);
        assertThat(((Number) summary.get("hit_rate")).doubleValue()).isEqualTo(1.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(((Number) results.get(0).get("next_ret")).doubleValue()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void missingSignalDayDoesNotFallBackOrWriteSnapshot() {
        // 2025-01-04 是周六；夹具日历覆盖该日但判定为交易日 → 因此这里确定是「数据缺失」。
        LocalDate day = LocalDate.of(2025, 1, 4);
        Map<String, Object> out = service().generateCandidates(day, 10);
        assertThat(out.get("status")).isEqualTo("NO_DATA");
        assertThat(out.get("trade_date")).isEqualTo(day.toString());
        assertThat((List<?>) out.get("candidates")).isEmpty();
        assertThat(out.get("calendar_trading_day")).isEqualTo(true);
        verify(marketDataRepository, org.mockito.Mockito.never()).findLatestTradeDateOnOrBefore(any());
        verify(t1CandidateRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * P0-3：必须区分「非交易日」与「交易日但数据缺失」——原先两者都返回 NO_DATA。
     */
    @Test
    void nonTradingDayIsReportedAsSuchNotAsMissingData() {
        LocalDate holiday = LocalDate.of(2025, 1, 4);
        when(tradingCalendarService.isTradingDay(holiday)).thenReturn(false);
        Map<String, Object> out = service().generateCandidates(holiday, 10);

        assertThat(out.get("status")).isEqualTo("NOT_A_TRADING_DAY");
        assertThat(out.get("calendar_trading_day")).isEqualTo(false);
        assertThat(out.get("note").toString()).contains("非交易日");
        assertThat((List<?>) out.get("candidates")).isEmpty();
        verify(t1CandidateRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * P0-3：日历不覆盖该日时**不得**妄断，必须给出第三态。
     * <p>真实数据：本地日历仅到 2026-08-19，而行情到 2026-10-12。
     * 若把区间外一律当作休市，会把 2026-09/10 的真实数据断崖说成「非交易日」。
     */
    @Test
    void dayOutsideCalendarCoverageIsUnknownNotTreatedAsHoliday() {
        LocalDate beyond = LocalDate.of(2026, 10, 12);
        when(tradingCalendarService.isTradingDay(beyond)).thenReturn(false);
        Map<String, Object> out = service().generateCandidates(beyond, 10);

        assertThat(out.get("status")).isEqualTo("UNKNOWN");
        assertThat(out.get("calendar_trading_day")).isEqualTo(false);
        assertThat(out.get("note").toString()).contains("不在交易日历覆盖范围内");
        assertThat(out.get("status")).isNotEqualTo("NOT_A_TRADING_DAY");
        assertThat((List<?>) out.get("candidates")).isEmpty();
    }

    /**
     * P0-3：恒为 false 的 {@code data_date_fallback} 必须消失——它暗示存在回退能力，
     * 而本服务从无回退路径（actualDate 自始至终等于 tradeDate）。
     */
    @Test
    void deadDataDateFallbackMetadataIsRemoved() {
        LocalDate day = LocalDate.of(2025, 1, 4);
        Map<String, Object> out = service().generateCandidates(day, 10);
        assertThat(out).doesNotContainKey("data_date_fallback");
        // 如实的替代字段必须存在
        assertThat(out.get("data_cutoff")).isEqualTo(day.toString());
        assertThat(out.get("requested_trade_date")).isEqualTo(day.toString());
        assertThat(out).containsKey("calendar_trading_day");
    }

    @Test
    void pricesOutsideThePitUniverseCannotEnterTheShortlist() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        List<MarketData> known = strongTrendBars("000001", "SZSE", day, 35);
        List<MarketData> unknown = strongTrendBars("000002", "SZSE", day, 35);
        List<MarketData> prices = new ArrayList<>(known); prices.addAll(unknown);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any())).thenReturn(prices);
        when(marketDataRepository.findByTradeDate(day)).thenReturn(List.of(known.getLast(), unknown.getLast()));
        // D-T1-06：只有 PIT 池内的键进入覆盖率分母
        stubActivePairs("000001", "SZSE", "000002", "SZSE");
        StockInfo info = new StockInfo();
        info.setStockCode("000001"); info.setExchangeType("SZSE"); info.setStockName("测试股份");
        info.setStatus(1); info.setListDate(day.minusYears(2)); stubUniverse(day, info);
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any())).thenReturn(Map.of("volume_ratio", 6.0));
        Map<String, Object> out = service().generateCandidates(day, 10);
        assertThat((List<?>) out.get("candidates")).hasSize(1);
        assertThat(out.get("total_scored")).isEqualTo(1);
        verify(limitUpEcologyService).stockLimitUpFeatures(any(), any(), any());
    }

    @Test
    void partialDailyRowsAreNotCalledAWeakSignalDay() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        StockInfo a = new StockInfo(), b = new StockInfo();
        a.setStockCode("000001"); a.setExchangeType("SZSE"); a.setStatus(1); a.setListDate(day.minusYears(2));
        b.setStockCode("000002"); b.setExchangeType("SZSE"); b.setStatus(1); b.setListDate(day.minusYears(2));
        when(pointInTimeUniverseProvider.loadAsOf(day)).thenReturn(new PointInTimeUniverseProvider.UniverseView(
                day, List.of(a,b), Map.of("000001|SZSE", a, "000002|SZSE", b), "security_master_history", "official", false, 2));
        when(marketDataRepository.findByTradeDate(day)).thenReturn(List.of(nextBar(day, "000001", "SZSE", 10, 10)));
        // D-T1-06：分母是「回看窗口内真实有行情的在市标的」，两只都在活跃集合内
        stubActivePairs("000001", "SZSE", "000002", "SZSE");
        Map<String, Object> out = service().generateCandidates(day, 10);
        assertThat(out.get("status")).isEqualTo("INCOMPLETE_DAILY_DATA");
        assertThat(out.get("daily_coverage")).isEqualTo(0.5);
        verify(t1CandidateRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * D-T1-06 回归：分母必须是「回看窗口内真实有行情的在市标的」。
     *
     * <p>缺陷复现：旧分母取 `stock_info` 全部在市键（本地实测 5,835，其中 284 个
     * stock_code 因北交所代码迁移同时存在 BSE 与 SSE 两个 exchange_type 行），
     * 而当日真实 bar 数最多只有 5,477 → 覆盖率上界 0.9386 < 0.95，
     * **每个信号日**都返回 INCOMPLETE_DAILY_DATA，候选恒空。
     *
     * <p>本测试构造 96 只在活跃窗口内 + 4 只仅存在于证券主表但窗口内无行情的陈旧键：
     * 修正后覆盖率 = 96/96 = 1.0 → 不被覆盖闸阻断；旧实现下 = 96/100 = 0.96 也会通过，
     * 故这里用更强的判据——**断言 denominator 只计活跃键**（daily_expected 语义）。
     */
    @Test
    void staleUniverseKeysAreExcludedFromCoverageDenominator() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        List<MarketData> range = strongTrendBars("000001", "SZSE", day, 35);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(range);
        Map<String, StockInfo> byKey = new HashMap<>();
        List<StockInfo> stocks = new ArrayList<>();
        // 1 只活跃标的
        StockInfo live = new StockInfo();
        live.setStockCode("000001"); live.setExchangeType("SZSE"); live.setStockName("活跃股");
        live.setStatus(1); live.setListDate(day.minusYears(2));
        byKey.put("000001|SZSE", live); stocks.add(live);
        // 200 只「证券主表在市、但回看窗口内完全没有行情」的陈旧键（模拟 BSE/SSE 双编码遗留）
        for (int i = 0; i < 200; i++) {
            StockInfo stale = new StockInfo();
            String code = String.format("920%03d", i);
            stale.setStockCode(code); stale.setExchangeType("SSE"); stale.setStockName("陈旧" + i);
            stale.setStatus(1); stale.setListDate(day.minusYears(2));
            byKey.put(code + "|SSE", stale); stocks.add(stale);
        }
        when(pointInTimeUniverseProvider.loadAsOf(day)).thenReturn(new PointInTimeUniverseProvider.UniverseView(
                day, stocks, byKey, "security_master_history", "official", false, stocks.size()));
        when(marketDataRepository.findByTradeDate(day)).thenReturn(List.of(range.getLast()));
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any())).thenReturn(Map.of("volume_ratio", 6.0));
        // 只有活跃键在回看窗口内出现
        stubActivePairs("000001", "SZSE");

        Map<String, Object> out = service().generateCandidates(day, 10);

        // 旧分母 = 201 → 覆盖率 1/201 = 0.005 < 0.95 → 误判 INCOMPLETE_DAILY_DATA
        assertThat(out.get("status")).isNotEqualTo("INCOMPLETE_DAILY_DATA");
        assertThat(out.get("daily_expected")).isEqualTo(1L);
        assertThat(out.get("daily_covered")).isEqualTo(1L);
        assertThat(out.get("daily_coverage")).isEqualTo(1.0);
    }

    /** 桩：回看窗口内活跃的 (code, exchange) 键。 */
    private void stubActivePairs(String... codeExchangePairs) {
        List<Object[]> pairs = new ArrayList<>();
        for (int i = 0; i < codeExchangePairs.length; i += 2) {
            pairs.add(new Object[]{codeExchangePairs[i], codeExchangePairs[i + 1]});
        }
        when(marketDataRepository.findDistinctStockPairsInRange(any(), any())).thenReturn(pairs);
    }

    @Test
    void sameDayRequestBeforeCloseDoesNotReadPrices() {
        T1CandidateService subject = service();
        org.springframework.test.util.ReflectionTestUtils.setField(subject, "clock", java.time.Clock.fixed(
                java.time.Instant.parse("2025-01-02T06:59:00Z"), java.time.ZoneId.of("Asia/Shanghai")));
        assertThat(subject.generateCandidates(LocalDate.of(2025, 1, 2), 10).get("status")).isEqualTo("DAILY_BAR_NOT_CLOSED");
        org.mockito.Mockito.verifyNoInteractions(marketDataRepository, t1CandidateRepository);
    }

    @Test
    void repeatedGenerationPreservesOriginalCandidatesAndSettlement() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(day);
        rec.setCandidatesJson("[{\"stock_code\":\"000001\",\"rule_score\":37}]");
        rec.setSettled(true);
        rec.setSettledJson("{\"summary\":{\"settled\":1}}");
        when(t1CandidateRepository.findByTradeDate(day)).thenReturn(Optional.of(rec));
        Map<String, Object> out = service().generateCandidates(day, 50);
        assertThat(out.get("snapshot_reused")).isEqualTo(true);
        assertThat(rec.getSettled()).isTrue();
        assertThat(rec.getCandidatesJson()).contains("37");
        org.mockito.Mockito.verifyNoInteractions(marketDataRepository);
        verify(t1CandidateRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void missingCandidatePriceDoesNotFinalizeSettlement() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(day);
        rec.setCandidatesJson("[{\"stock_code\":\"000001\",\"exchange_type\":\"SZSE\",\"close\":10}]");
        when(t1CandidateRepository.findByTradeDate(day)).thenReturn(Optional.of(rec));
        when(tradingCalendarService.findTradingDays(day, day.plusDays(7))).thenReturn(List.of(day, day.plusDays(1)));
        Map<String, Object> out = service().settleCandidates(day);
        assertThat(out.get("status")).isEqualTo("PARTIAL_DATA");
        assertThat(rec.getSettled()).isFalse();
    }

    @Test
    void finalizedSettlementIsNotRecomputedFromRevisedPrices() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(day);
        rec.setSettled(true);
        rec.setSettledJson("{\"summary\":{\"avg_next_ret\":0.1},\"results\":[]}");
        when(t1CandidateRepository.findByTradeDate(day)).thenReturn(Optional.of(rec));
        assertThat(service().settleCandidates(day).get("status")).isEqualTo("ALREADY_SETTLED");
        org.mockito.Mockito.verifyNoInteractions(marketDataRepository, tradingCalendarService);
        verify(t1CandidateRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void benchmarkPairsSignalCloseWithExactSettlementCloseAndCountsAbstentions() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(day);
        rec.setTopN(10);
        rec.setSettled(true);
        rec.setCandidatesJson("[{},{}]");
        rec.setSettledJson("{\"next_trade_date\":\"2025-01-03\",\"summary\":{\"settled\":2,\"hit_count\":2,\"avg_next_ret\":0.1}}");
        T1Candidate empty = new T1Candidate();
        empty.setTradeDate(day.plusDays(1));
        empty.setCandidatesJson("[]");
        T1Candidate partial = new T1Candidate();
        partial.setTradeDate(day.plusDays(4));
        partial.setCandidatesJson("[{},{}]");
        partial.setSettledJson("{\"summary\":{\"settled\":1,\"avg_next_ret\":0.9}}");
        when(t1CandidateRepository.findByTradeDateBetweenOrderByTradeDateAsc(day, day.plusDays(4))).thenReturn(List.of(rec, empty, partial));
        List<com.quant.entity.IndexQuote> index = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var quote = new com.quant.entity.IndexQuote();
            quote.setTradeDate(day.plusDays(i));
            quote.setClosePrice(BigDecimal.valueOf(new double[]{100, 110, 220}[i]));
            index.add(quote);
        }
        when(indexQuoteRepository.findByIndexCodeAndTradeDateBetweenOrderByTradeDateAsc(any(), any(), any())).thenReturn(index);
        Map<String, Object> out = service().strategyStats(day, day.plusDays(4));
        Map<?, ?> bench = (Map<?, ?>) out.get("benchmark");
        assertThat(bench.get("index_avg_daily_return")).isEqualTo(0.1);
        assertThat(bench.get("excess_avg_daily_return")).isEqualTo(0.0);
        assertThat(out.get("total_settled")).isEqualTo(2);
        assertThat(out.get("total_candidates")).isEqualTo(4);
        assertThat(out.get("zero_candidate_days")).isEqualTo(1);
        assertThat(out.get("unsettled_days")).isEqualTo(1);
    }

    /**
     * 构造「强信号」日线序列，使 rule_score 能真实越过入选阈值（D-T1-01 后阈值 35、满分 50）。
     *
     * <p><b>为什么不能沿用旧的线性 ramp 夹具</b>：旧夹具收盘价 10.0 起、每日 +0.1，
     * 其 rule_score 只有约 12 分（ret5≈1.6 + 量能 + maPos≈0.2）。它过去之所以能进候选，
     * 靠的正是已被删除的「+30 涨停」死因子。移除该因子后，夹具必须真正体现强信号特征。
     *
     * <p><b>本夹具的精确算术</b>（前 {@code days-7} 日横盘在 10.0，最后 7 日加速上冲到 20.4）：
     * <ul>
     *   <li>动量 ret5 = 20.4/13.4 − 1 = 52.2% → min(20, 0.522×100×0.4) = 20（触上限）</li>
     *   <li>趋势 maPos = 20.4/12.43 − 1 ≈ 64% → min(15, 0.64×15) ≈ 9.6</li>
     *   <li>量能（由 mock 注入）：vr=6.0 → min(15, (6.0−1)×10) = 15（触上限）</li>
     * </ul>
     * 合计 ≈ 44.6 分，明确越过 30 的入选阈值且有余量（不会因小数点舍入偶发失败）。
     *
     * <p><b>价格序列为何这样取</b>：主板涨跌停 ±10%，末值必须落在「前收 × 1.10」之内，
     * 否则会被「收盘涨停」预过滤剔除——这本身就是 D-T1-01 的核心机制。故末段每日涨幅
     * 都控制在 10% 以内（13.4 → 15.2 → 18.6 → 20.4 会超，故末段取 13.4 → 15.2 → 18.6 → 20.4
     * 中的 18.6 作为倒数第二值，使 20.4/18.6 = +9.7% ≤ 10%）。
     */
    private static List<MarketData> strongTrendBars(String code, String ex, LocalDate end, int days) {
        List<MarketData> bars = new ArrayList<>();
        int spikeStart = Math.max(days - 7, 0);
        double[] spikeCloses = {10.4, 11.2, 12.2, 13.4, 15.2, 18.6, 20.4};
        double prevClose = 10.0;
        for (int i = 0; i < days; i++) {
            LocalDate date = end.minusDays((days - 1 - i) * 1L);
            double close;
            if (i < spikeStart) {
                close = 10.0;
            } else {
                close = spikeCloses[Math.min(i - spikeStart, spikeCloses.length - 1)];
            }
            MarketData m = new MarketData();
            m.setStockCode(code);
            m.setExchangeType(ex);
            m.setTradeDate(date);
            m.setClosePrice(BigDecimal.valueOf(close));
            m.setPreClose(BigDecimal.valueOf(prevClose));
            m.setOpenPrice(BigDecimal.valueOf(close * 0.99));
            m.setHighPrice(BigDecimal.valueOf(close + 0.2));
            m.setLowPrice(BigDecimal.valueOf(close * 0.97));
            m.setVolume(1_000_000L);
            m.setAmount(BigDecimal.valueOf(close * 3_000_000)); // 日均成交额 ≈30M+，通过 minAvgAmount 闸
            bars.add(m);
            prevClose = close;
        }
        return bars;
    }

    private static List<MarketData> rangeBars(String code, String ex, LocalDate end, int days) {
        List<MarketData> bars = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = end.minusDays((days - 1 - i) * 1L);
            double close = 10 + i * 0.1;
            MarketData m = new MarketData();
            m.setStockCode(code);
            m.setExchangeType(ex);
            m.setTradeDate(date);
            m.setClosePrice(BigDecimal.valueOf(close));
            m.setPreClose(BigDecimal.valueOf(i == 0 ? close : close - 0.1));
            m.setOpenPrice(BigDecimal.valueOf(close));
            m.setHighPrice(BigDecimal.valueOf(close + 0.2));
            m.setLowPrice(BigDecimal.valueOf(close - 0.2));
            m.setVolume(1_000_000L);
            m.setAmount(BigDecimal.valueOf(close * 3_000_000)); // 日均成交额 ≈30M+，通过 minAvgAmount 闸
            bars.add(m);
        }
        return bars;
    }

    private static MarketData nextBar(LocalDate date, String code, String ex, double close, double preClose) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setOpenPrice(BigDecimal.valueOf(close * 0.99));
        m.setPreClose(BigDecimal.valueOf(preClose));
        m.setHighPrice(BigDecimal.valueOf(close * 1.02));
        m.setLowPrice(BigDecimal.valueOf(close * 0.98));
        m.setVolume(1_000_000L);
        return m;
    }
}
