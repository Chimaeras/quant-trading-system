package com.quant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.service.scoring.factor.StrategyCatalogService;
import com.quant.service.scoring.factor.UnifiedScoreReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器层契约测试：把「前端真正会收到什么 JSON」变成可执行断言。
 *
 * <p><b>为什么需要它</b>：此前的验证都停在服务层——服务返回的 Map 键名对不对，
 * 与"HTTP 上跑出来的 JSON 键名对不对"是两件事。中间还隔着：
 * 路由是否注册、{@code ApiResponse} 信封（{@code code/message/data}）是否正确、
 * Jackson 是否正确序列化嵌套结构。
 * 任何一环错了，服务层测试都会全绿而前端拿到 404 或空数组。
 *
 * <p>用 {@code standaloneSetup}（不启动 Spring 容器、不连库）：
 * <ul>
 *   <li>不会触发 Flyway —— 这很关键：直接 {@code @SpringBootTest} 会把尚未实施的
 *       V30 迁移应用到真实库上，属于未授权的数据层变更。</li>
 *   <li>只注册被判定的控制器，失败原因归因清晰。</li>
 * </ul>
 *
 * <p>覆盖率边界（诚实标注）：本测试用 mock 的服务，因此证明的是
 * <b>路由 + 信封 + 序列化 + 契约键名</b>，不证明真实的算分结果。
 * 真实数据的算分由 {@code UnifiedScoreReadServiceTest} 与真实库只读脚本覆盖。
 */
class UnifiedScoringControllerContractTest {

    private UnifiedScoreReadService readService;
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        readService = mock(UnifiedScoreReadService.class);
        // 策略目录服务是纯计算无依赖的，直接用真实实现，避免把目录契约也 mock 掉
        UnifiedScoringController controller = new UnifiedScoringController(
                mock(com.quant.service.scoring.UnifiedScoringService.class),
                mock(com.quant.service.scoring.factor.UnifiedFactorScoringService.class),
                mock(com.quant.service.scoring.factor.UnifiedFactorComparisonService.class),
                new StrategyCatalogService(),
                readService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static Map<String, Object> readServiceResponse() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rank_no", 1);
        item.put("stock_code", "600000");
        item.put("exchange_type", "SSE");
        item.put("stock_name", "浦发银行");
        item.put("strategy_type", "CLASSIC_SELECTOR");
        item.put("final_score", 87.5);
        item.put("total_score", 87.5);
        item.put("trend_score", 12.25);
        item.put("momentum_score", 4.5);
        item.put("volume_score", 3.0);
        item.put("risk_penalty", -1.5);
        item.put("ret20", 4.17536534446763);
        item.put("ret60", 13.667425968109349);
        item.put("volatility20", 0.0024171260319901054);
        item.put("max_drawdown60", 0.0);
        item.put("avg_amount20", 2.4475E7);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("unified_engine", true);
        extra.put("factor_contributions", List.of(Map.of("factor", "TREND_MA", "weight", 0.2)));
        item.put("extra_metrics", extra);

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("legacy_strategy_type", "CLASSIC_SELECTOR");
        block.put("legacy_ui_mode", "classic");
        block.put("display_name", "经典多因子");
        block.put("factor_version", "unified_factor_classic_v1");
        block.put("ranked_count", 100);
        block.put("rejected_count", 5);
        block.put("returned_count", 1);
        block.put("rejection_breakdown", Map.of("ST_NAME", 2));
        block.put("normalization_mode", "LOG_MV_ONLY");
        block.put("industry_neutralization_skipped", true);
        block.put("market_cap_neutralization_skipped", false);
        block.put("items", List.of(item));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("trade_date", "2026-10-14");
        out.put("load_count", 1);
        out.put("single_load", true);
        out.put("candidate_count", 105);
        out.put("universe_size", 5835);
        out.put("tradability_rejected", 2);
        out.put("listing_days_rejected", 0);
        out.put("rejection_breakdown", Map.of("ST_NAME", 2));
        out.put("persisted", false);
        out.put("strategies", List.of(block));
        return out;
    }

    @Test
    void allStrategiesEndpointIsRoutedAndWrappedInTheApiEnvelope() throws Exception {
        when(readService.runAllStrategies(any())).thenReturn(readServiceResponse());

        String body = mockMvc.perform(post("/api/scoring/unified/engine/all-strategies")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = mapper.readTree(body);
        // 信封：前端 unwrap() 依赖 code/message/data 三个键
        assertThat(root.has("code")).isTrue();
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("data")).isTrue();
        assertThat(root.get("data").get("success").asBoolean()).isTrue();
        assertThat(root.get("data").get("single_load").asBoolean()).isTrue();
    }

    @Test
    void responseCarriesExactlyTheKeysTheFrontendReads() throws Exception {
        when(readService.runAllStrategies(any())).thenReturn(readServiceResponse());

        String body = mockMvc.perform(post("/api/scoring/unified/engine/all-strategies")
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = mapper.readTree(body).get("data");

        // AllStrategiesResult 接口里声明的键必须真的出现
        for (String k : List.of("success", "trade_date", "load_count", "single_load",
                "candidate_count", "universe_size", "tradability_rejected",
                "listing_days_rejected", "rejection_breakdown", "persisted", "strategies")) {
            assertThat(data.has(k)).as("缺少键 %s", k).isTrue();
        }

        JsonNode block = data.get("strategies").get(0);
        for (String k : List.of("legacy_strategy_type", "legacy_ui_mode", "display_name",
                "factor_version", "ranked_count", "rejected_count", "returned_count",
                "normalization_mode", "items")) {
            assertThat(block.has(k)).as("策略块缺少键 %s", k).isTrue();
        }
        // 前端 currentStrategy() 靠 legacy_strategy_type 选 tab
        assertThat(block.get("legacy_strategy_type").asText()).isEqualTo("CLASSIC_SELECTOR");

        JsonNode item = block.get("items").get(0);
        for (String k : List.of("rank_no", "stock_code", "exchange_type", "stock_name",
                "strategy_type", "final_score", "trend_score", "momentum_score",
                "volume_score", "ret20", "ret60", "volatility20", "max_drawdown60",
                "avg_amount20", "extra_metrics")) {
            assertThat(item.has(k)).as("条目缺少键 %s", k).isTrue();
        }
        assertThat(item.get("extra_metrics").get("unified_engine").asBoolean()).isTrue();
    }

    @Test
    void smallVolatilitySurvivesJsonSerializationWithoutBecomingZero() throws Exception {
        when(readService.runAllStrategies(any())).thenReturn(readServiceResponse());

        String body = mockMvc.perform(post("/api/scoring/unified/engine/all-strategies")
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode item = mapper.readTree(body).get("data").get("strategies").get(0).get("items").get(0);

        // 端到端复现 §8.10.2 的缺陷面：0.002417 不得在序列化后变成 0
        assertThat(item.get("volatility20").asDouble()).isEqualTo(0.0024171260319901054);
        // 真实 0 必须保持 0，不能变成 null 或被省略
        assertThat(item.get("max_drawdown60").isNull()).isFalse();
        assertThat(item.get("max_drawdown60").asDouble()).isEqualTo(0.0);
    }

    @Test
    void catalogEndpointIsRoutedAndExposesAllFiveStrategies() throws Exception {
        String body = mockMvc.perform(get("/api/scoring/unified/strategies"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(body).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(5);

        List<String> types = new java.util.ArrayList<>();
        List<String> uiModes = new java.util.ArrayList<>();
        for (JsonNode n : data) {
            types.add(n.get("legacy_strategy_type").asText());
            uiModes.add(n.get("legacy_ui_mode").asText());
        }
        // 前端 5 个 tab 的映射来源
        assertThat(types).containsExactly("CLASSIC_SELECTOR", "TREND", "OVERSOLD_REBOUND",
                "COMPREHENSIVE", "MULTI_DIM");
        assertThat(uiModes).containsExactly("classic", "trend", "oversold",
                "comprehensive", "multi_dim");
    }

    @Test
    void unknownStrategyInCatalogReturns404RatherThanSilentEmpty() throws Exception {
        mockMvc.perform(get("/api/scoring/unified/strategies/NOT_A_STRATEGY"))
                .andExpect(status().isNotFound());
    }

    @Test
    void knownStrategyInCatalogReturnsItsWeights() throws Exception {
        String body = mockMvc.perform(get("/api/scoring/unified/strategies/TREND"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(body).get("data");
        assertThat(data.get("legacy_strategy_type").asText()).isEqualTo("TREND");
        assertThat(data.get("weights").isArray()).isTrue();
        assertThat(data.get("weights").size()).isGreaterThan(0);
        // 每个权重项都要能被前端渲染
        JsonNode w = data.get("weights").get(0);
        for (String k : List.of("factor", "label", "weight", "normalized_weight",
                "fundamental", "risk")) {
            assertThat(w.has(k)).as("权重项缺少键 %s", k).isTrue();
        }
    }

    @Test
    void missingRequestBodyIsToleratedBecauseEndpointAcceptsOptionalBody() throws Exception {
        when(readService.runAllStrategies(any())).thenReturn(readServiceResponse());

        // @RequestBody(required = false)：前端不传 body 也应工作，并收到空 Map 而非 400
        mockMvc.perform(post("/api/scoring/unified/engine/all-strategies"))
                .andExpect(status().isOk());

        verify(readService).runAllStrategies(any());
    }
}
