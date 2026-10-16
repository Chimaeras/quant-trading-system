package com.quant.service.scoring;

import com.quant.entity.SelectorResult;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.UnifiedScoringTaskRepository;
import com.quant.service.MultiDimScoringService;
import com.quant.service.OversoldReboundScoringService;
import com.quant.service.StockSelectorService;
import com.quant.service.StockTrendScoringService;
import com.quant.service.scoring.factor.UnifiedFactorScoringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 D 测试：统一打分落库的<b>幂等去重</b>（重复运行治理）。
 *
 * <p><b>要防住的缺陷</b>：{@code persistUnifiedInTransaction} 原本每次都生成新的
 * {@code resultId}，而"是否已存在同请求结果"的检查只在调用方传入固定 {@code resultId} 时
 * 才有机会执行。于是同一请求执行两次必然产生两条结果行——
 * 这正是生产库 {@code (strategy, trade_date, stock)} 重复率达 41% 的成因。
 *
 * <p>本测试锁住修复后的语义：
 * <ul>
 *   <li>同策略 + 同指纹 + 同交易日已有 <b>SUCCESS</b> 结果 → 复用，不再插入新行；</li>
 *   <li>{@code dedupe=false} → 强制新建；</li>
 *   <li>既有记录非 SUCCESS（RUNNING/PARTIAL/FAILED）→ 不复用，重新计算；</li>
 *   <li>不同交易日即使指纹相同 → <b>不得</b>误判为同一次运行
 *       （指纹由请求参数构成、不含交易日，这是最危险的误判点）。</li>
 * </ul>
 */
class UnifiedScoringServiceDedupeTest {

    private final StockTrendScoringService trendScoringService = mock(StockTrendScoringService.class);
    private final MultiDimScoringService multiDimScoringService = mock(MultiDimScoringService.class);
    private final UnifiedFactorScoringService unifiedFactorScoringService =
            mock(UnifiedFactorScoringService.class);
    private final SelectorResultRepository selectorResultRepository = mock(SelectorResultRepository.class);
    private final SelectorResultItemRepository selectorResultItemRepository =
            mock(SelectorResultItemRepository.class);
    private final UnifiedScoringTaskRepository unifiedScoringTaskRepository =
            mock(UnifiedScoringTaskRepository.class);

    private UnifiedScoringService service;

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 13);
    private static final String EXISTING_RESULT_ID = "US-EXISTING-0001";

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        // TransactionTemplate 需要一个可用的 TransactionStatus，直接给 mock 让它原样执行回调
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new UnifiedScoringService(
                trendScoringService,
                mock(ComprehensiveScoringService.class),
                multiDimScoringService,
                mock(StockSelectorService.class),
                mock(OversoldReboundScoringService.class),
                unifiedFactorScoringService,
                selectorResultRepository,
                selectorResultItemRepository,
                unifiedScoringTaskRepository,
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class),
                txManager);

        // 旧策略实现返回一个最小可落库的结果。
        // 注意 trade_date 必须取自请求：persistUnified 用的是 rawResult 里的 trade_date
        // （即策略实际算出的交易日），而不是请求参数。硬编码日期会让
        // "不同交易日不应互相命中" 这条断言失去意义。
        when(trendScoringService.run(any())).thenAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            Object requested = req == null ? null : req.get("tradeDate");
            return sampleResult(requested == null ? TRADE_DATE.toString() : String.valueOf(requested));
        });
        when(selectorResultItemRepository.findStockKeysByResultId(anyString())).thenReturn(java.util.Set.of());
        when(selectorResultItemRepository.countByResultId(anyString())).thenReturn(0L);
    }

    private Map<String, Object> sampleResult(String tradeDate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stock_code", "600000");
        item.put("exchange_type", "SSE");
        item.put("stock_name", "浦发银行");
        item.put("total_score", 66.6);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", List.of(item));
        r.put("trade_date", tradeDate);
        r.put("factor_version", "trend_signal_v2");
        return r;
    }

    private static SelectorResult successRow(String resultId, LocalDate date) {
        SelectorResult sr = new SelectorResult();
        sr.setResultId(resultId);
        sr.setStrategyType("TREND");
        sr.setTradeDate(date);
        sr.setRunStatus("SUCCESS");
        return sr;
    }

    private Map<String, Object> runRequest(Map<String, Object> extra) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("strategy", "TREND");
        req.put("tradeDate", TRADE_DATE.toString());
        req.put("persist", true);
        req.put("topN", 10);
        req.putAll(extra);
        return req;
    }

    @Test
    void sameRequestReusesExistingSuccessResultInsteadOfInsertingANewRow() {
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        anyString(), anyString(), any(), anyString()))
                .thenReturn(Optional.of(successRow(EXISTING_RESULT_ID, TRADE_DATE)));

        Map<String, Object> out = service.run(runRequest(Map.of()));

        assertThat(out.get("result_id")).isEqualTo(EXISTING_RESULT_ID);
        assertThat(out.get("deduplicated")).isEqualTo(true);
        assertThat(String.valueOf(out.get("deduplicated_note"))).contains("dedupe=false");

        // 关键：不得写入任何新的结果行
        verify(selectorResultRepository, never()).save(any(SelectorResult.class));
    }

    @Test
    void dedupeFalseForcesAFreshRunAndInserts() {
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        anyString(), anyString(), any(), anyString()))
                .thenReturn(Optional.of(successRow(EXISTING_RESULT_ID, TRADE_DATE)));
        when(selectorResultRepository.findByResultId(anyString())).thenReturn(Optional.empty());

        Map<String, Object> out = service.run(runRequest(Map.of("dedupe", false)));

        assertThat(out.get("deduplicated")).isEqualTo(false);
        assertThat(out.get("result_id")).isNotEqualTo(EXISTING_RESULT_ID);

        // 明确要求重算时必须真的落库
        ArgumentCaptor<SelectorResult> captor = ArgumentCaptor.forClass(SelectorResult.class);
        verify(selectorResultRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(SelectorResult::getResultId)
                .contains((String) out.get("result_id"));
    }

    @Test
    void nonSuccessExistingResultIsNotReused() {
        // 只查找 SUCCESS，因此 RUNNING/PARTIAL/FAILED 不会命中。
        // 这里模拟仓储在限定 SUCCESS 时返回空 —— 即"没有可复用的成功结果"
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        anyString(), anyString(), any(), eq("SUCCESS")))
                .thenReturn(Optional.empty());
        when(selectorResultRepository.findByResultId(anyString())).thenReturn(Optional.empty());

        Map<String, Object> out = service.run(runRequest(Map.of()));

        assertThat(out.get("deduplicated")).isEqualTo(false);
        assertThat(out.get("result_id")).isNotEqualTo(EXISTING_RESULT_ID);
    }

    @Test
    void lookupIsScopedToTheTradeDateSoDifferentDaysAreNotConfused() {
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        anyString(), anyString(), any(), anyString()))
                .thenReturn(Optional.of(successRow(EXISTING_RESULT_ID, TRADE_DATE)));
        when(selectorResultRepository.findByResultId(anyString())).thenReturn(Optional.empty());

        service.run(runRequest(Map.of("tradeDate", "2026-10-14")));

        // 必须带上交易日查询。指纹由请求参数构成、<b>不含</b>交易日，
        // 若只用 (策略, 指纹) 查找，10-14 的运行会被误判成 10-13 已跑过而直接复用旧结果。
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(selectorResultRepository)
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        anyString(), anyString(), dateCaptor.capture(), anyString());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 10, 14));
    }
}
