package com.quant.service.scoring.factor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link StrategyCatalog} 暴露成只读元数据，供前端渲染策略入口。
 *
 * <p><b>为什么需要</b>：此前「有哪 5 个策略」「各自叫什么」「对应哪个 UI tab」分别硬编码在
 * Java 与 {@code StockPicker.vue} 里，两处各写一份。结果是加策略要改两处、改名必然漂移。
 * 本服务让目录成为<b>唯一真相</b>，前端只做展示。
 *
 * <p><b>只读、无副作用</b>：不查库、不落库，因此可以在任何环境安全调用。
 */
@Service
public class StrategyCatalogService {

    /** 供前端渲染的完整策略清单。 */
    public List<Map<String, Object>> listStrategies() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StrategyDefinition d : StrategyCatalog.all()) {
            out.add(describe(d));
        }
        return out;
    }

    /** 单个策略的元数据；未知返回 null。 */
    public Map<String, Object> describeOne(String legacyStrategyType) {
        StrategyDefinition d = StrategyCatalog.byLegacyStrategyType(legacyStrategyType);
        return d == null ? null : describe(d);
    }

    private Map<String, Object> describe(StrategyDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", d.code());
        m.put("display_name", d.displayName());
        m.put("factor_version", d.factorVersion());
        m.put("legacy_strategy_type", d.legacyStrategyType());
        // 前端用它决定挂到哪个 tab；null 表示后端存在但前端未暴露入口
        m.put("legacy_ui_mode", d.legacyUiMode());
        m.put("min_bars", d.minBars());
        m.put("min_avg_amount", d.minAvgAmount());
        m.put("min_listing_days", d.minListingDays());
        m.put("missing_data_policy", d.missingDataPolicy().name());
        m.put("min_available_weight_ratio", d.minAvailableWeightRatio());
        m.put("require_ma20_above_ma60", d.requireMa20AboveMa60());

        List<Map<String, Object>> weights = new ArrayList<>();
        for (Map.Entry<FactorId, Double> e : d.weights().entrySet()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("factor", e.getKey().name());
            w.put("label", e.getKey().label());
            w.put("weight", e.getValue());
            w.put("normalized_weight", d.normalizedWeights().get(e.getKey()));
            w.put("fundamental", e.getKey().isFundamental());
            w.put("risk", e.getKey().isRisk());
            weights.add(w);
        }
        m.put("weights", weights);

        List<String> required = new ArrayList<>();
        for (FactorId f : d.requiredFactors()) {
            required.add(f.name());
        }
        m.put("required_factors", required);

        // 让前端与调用方能直接看到「这个策略有多少权重依赖基本面数据」，
        // 而当前库 PE/PB 与财务快照填充率为 0——这个数字就是缺口的量化。
        m.put("non_fundamental_weight_share", d.nonFundamentalWeightShare());
        m.put("fundamental_dependent", d.nonFundamentalWeightShare() < 0.999);
        return m;
    }
}
