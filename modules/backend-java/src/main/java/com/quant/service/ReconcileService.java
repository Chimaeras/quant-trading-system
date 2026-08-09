package com.quant.service;

import com.quant.entity.PositionInfo;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.UserAccount;
import com.quant.repository.PositionInfoRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 持仓与选股信号对账：比较目标持仓（选股 TopN）与实际持仓的差异。
 *
 * <p>口径：
 * <ul>
 *   <li>目标持仓 = 指定选股结果的 TopN 候选（按 rankNo 升序）。</li>
 *   <li>实际持仓 = 账户当前 PositionInfo（volume > 0）。</li>
 *   <li>to_buy：在候选中但未持有（建议买入关注）。</li>
 *   <li>to_sell：持有但不在候选中（建议减仓/退出关注）。</li>
 *   <li>held_in_candidates：已持有且在候选中。</li>
 *   <li>仅作决策辅助，不自动下单；不含目标仓位比例（需结合资金/风控，后续扩展）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final PositionInfoRepository positionInfoRepository;
    private final UserAccountRepository userAccountRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> reconcile(Long userId, String resultId, int topN) {
        UserAccount account = userAccountRepository.findFirstByUserIdAndStatus(userId, 1)
                .orElseThrow(() -> new IllegalArgumentException("用户 " + userId + " 无活跃账户"));

        SelectorResult result = selectorResultRepository.findByResultId(resultId)
                .orElseThrow(() -> new IllegalArgumentException("选股结果 " + resultId + " 不存在"));

        List<SelectorResultItem> items = selectorResultItemRepository
                .findByResultIdOrderByRankNoAsc(result.getResultId()).stream()
                .limit(Math.max(1, topN))
                .toList();

        // 实际持仓 = 在线账户（sessionId=0）当前 PositionInfo（volume > 0）；
        // 历史演练会话持仓按 V2-MP-021 隔离，不计入在线对账。
        List<PositionInfo> positions = positionInfoRepository
                .findByAccountIdAndSessionIdOrderByStockCodeAsc(account.getId(), 0L).stream()
                .filter(p -> p.getVolume() != null && p.getVolume() > 0)
                .toList();

        // key = stockCode|exchangeType
        Set<String> targetKeys = new TreeSet<>();
        List<Map<String, Object>> targetPositions = new ArrayList<>();
        for (SelectorResultItem it : items) {
            String key = it.getStockCode() + "|" + it.getExchangeType();
            targetKeys.add(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stock_code", it.getStockCode());
            m.put("exchange_type", it.getExchangeType());
            m.put("stock_name", it.getStockName());
            m.put("rank_no", it.getRankNo());
            m.put("final_score", it.getFinalScore());
            targetPositions.add(m);
        }

        Set<String> actualKeys = new TreeSet<>();
        List<Map<String, Object>> actualPositions = new ArrayList<>();
        for (PositionInfo p : positions) {
            String key = p.getStockCode() + "|" + p.getExchangeType();
            actualKeys.add(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stock_code", p.getStockCode());
            m.put("exchange_type", p.getExchangeType());
            m.put("stock_name", p.getStockName());
            m.put("volume", p.getVolume());
            m.put("available_volume", p.getAvailableVolume());
            m.put("avg_cost", p.getAvgCost());
            m.put("current_price", p.getCurrentPrice());
            m.put("profit_rate", p.getProfitRate());
            actualPositions.add(m);
        }

        // to_buy：候选 - 持有
        List<Map<String, Object>> toBuy = new ArrayList<>();
        for (Map<String, Object> t : targetPositions) {
            String key = t.get("stock_code") + "|" + t.get("exchange_type");
            if (!actualKeys.contains(key)) {
                toBuy.add(t);
            }
        }
        // to_sell：持有 - 候选
        List<Map<String, Object>> toSell = new ArrayList<>();
        for (Map<String, Object> a : actualPositions) {
            String key = a.get("stock_code") + "|" + a.get("exchange_type");
            if (!targetKeys.contains(key)) {
                toSell.add(a);
            }
        }
        // held_in_candidates：交集
        List<Map<String, Object>> heldInCandidates = new ArrayList<>();
        for (Map<String, Object> a : actualPositions) {
            String key = a.get("stock_code") + "|" + a.get("exchange_type");
            if (targetKeys.contains(key)) {
                heldInCandidates.add(a);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("account_id", account.getId());
        out.put("result_id", result.getResultId());
        out.put("strategy", result.getStrategyType());
        out.put("trade_date", result.getTradeDate() != null ? result.getTradeDate().toString() : "");
        out.put("top_n", topN);
        out.put("target_count", targetPositions.size());
        out.put("actual_count", actualPositions.size());
        out.put("to_buy", toBuy);
        out.put("to_sell", toSell);
        out.put("held_in_candidates", heldInCandidates);
        out.put("note", "仅作决策辅助，不自动下单；目标仓位比例需结合资金与风控单独评估");
        return out;
    }
}
