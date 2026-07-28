package com.quant.service;

import com.quant.entity.PositionInfo;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.UserAccount;
import com.quant.repository.PositionInfoRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconcileServiceTest {

    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;
    @Mock
    private PositionInfoRepository positionInfoRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    private ReconcileService service() {
        return new ReconcileService(selectorResultRepository, selectorResultItemRepository,
                positionInfoRepository, userAccountRepository);
    }

    private static UserAccount account(Long id, Long userId) {
        UserAccount a = new UserAccount();
        a.setId(id);
        a.setUserId(userId);
        a.setStatus(1);
        return a;
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        return r;
    }

    private static SelectorResultItem item(String resultId, int rank, String code, String exchange) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setRankNo(rank);
        it.setStockCode(code);
        it.setExchangeType(exchange);
        it.setStockName("测试" + code);
        it.setFinalScore(80.0 - rank);
        return it;
    }

    private static PositionInfo position(Long accountId, String code, String exchange, int volume) {
        PositionInfo p = new PositionInfo();
        p.setAccountId(accountId);
        p.setStockCode(code);
        p.setExchangeType(exchange);
        p.setVolume(volume);
        p.setAvailableVolume(volume);
        p.setAvgCost(BigDecimal.TEN);
        p.setCurrentPrice(BigDecimal.valueOf(11));
        p.setProfitRate(BigDecimal.valueOf(0.1));
        return p;
    }

    @Test
    void reconcileClassifiesToBuyToSellAndHeld() {
        Long userId = 1L;
        Long accountId = 10L;
        when(userAccountRepository.findFirstByUserIdAndStatus(userId, 1))
                .thenReturn(Optional.of(account(accountId, userId)));
        SelectorResult r = result("R1", "CLASSIC_SELECTOR", LocalDate.of(2025, 1, 2));
        when(selectorResultRepository.findByResultId("R1")).thenReturn(Optional.of(r));
        // 候选 Top3：000001 / 000002 / 000003
        when(selectorResultItemRepository.findByResultIdOrderByRankNoAsc("R1"))
                .thenReturn(List.of(
                        item("R1", 1, "000001", "SZSE"),
                        item("R1", 2, "000002", "SZSE"),
                        item("R1", 3, "000003", "SZSE")
                ));
        // 实际持仓：000001（在候选）、000004（不在候选）
        when(positionInfoRepository.findByAccountIdOrderByStockCodeAsc(accountId))
                .thenReturn(List.of(
                        position(accountId, "000001", "SZSE", 1000),
                        position(accountId, "000004", "SZSE", 500)
                ));

        Map<String, Object> out = service().reconcile(userId, "R1", 3);

        assertThat(out.get("target_count")).isEqualTo(3);
        assertThat(out.get("actual_count")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toBuy = (List<Map<String, Object>>) out.get("to_buy");
        assertThat(toBuy).hasSize(2);
        assertThat(toBuy.stream().map(m -> m.get("stock_code")))
                .containsExactly("000002", "000003");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toSell = (List<Map<String, Object>>) out.get("to_sell");
        assertThat(toSell).hasSize(1);
        assertThat(toSell.get(0).get("stock_code")).isEqualTo("000004");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> held = (List<Map<String, Object>>) out.get("held_in_candidates");
        assertThat(held).hasSize(1);
        assertThat(held.get(0).get("stock_code")).isEqualTo("000001");
    }

    @Test
    void reconcileRespectsTopNLimit() {
        Long userId = 1L;
        when(userAccountRepository.findFirstByUserIdAndStatus(eq(userId), eq(1)))
                .thenReturn(Optional.of(account(10L, userId)));
        when(selectorResultRepository.findByResultId("R1"))
                .thenReturn(Optional.of(result("R1", "TREND", LocalDate.of(2025, 1, 2))));
        when(selectorResultItemRepository.findByResultIdOrderByRankNoAsc("R1"))
                .thenReturn(List.of(
                        item("R1", 1, "000001", "SZSE"),
                        item("R1", 2, "000002", "SZSE"),
                        item("R1", 3, "000003", "SZSE"),
                        item("R1", 4, "000004", "SZSE"),
                        item("R1", 5, "000005", "SZSE")
                ));
        when(positionInfoRepository.findByAccountIdOrderByStockCodeAsc(any()))
                .thenReturn(List.of());

        Map<String, Object> out = service().reconcile(userId, "R1", 3);

        assertThat(out.get("target_count")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toBuy = (List<Map<String, Object>>) out.get("to_buy");
        assertThat(toBuy).hasSize(3);
    }
}
