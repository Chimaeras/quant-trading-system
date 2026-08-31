package com.quant.service;

import com.quant.service.PortfolioBacktestService.BuyDecision;
import com.quant.service.PortfolioBacktestService.DayBudgetGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：当日开盘买入「等权但动态」现金预算（R14-P2-2 修复）。
 *
 * <p>旧实现用一次性 {@code budgetEach = cash / n} 给当日每个候选均摊预算。一旦某候选被拒
 * （未花钱）或买得便宜，其额度不会让渡给后续标的，导致后续高价标的被
 * {@code INSUFFICIENT_CASH_FOR_LOT} 误拒，即便账户实际剩余现金足够。
 *
 * <p>本测试直接驱动 {@link DayBudgetGuard}（预算追踪）与 {@link PortfolioBacktestService#decideBuy}
 * （单笔成交/拒绝判定）——即买入循环实际调用的生产代码路径，验证：
 * <ol>
 *   <li>被拒候选的额度会重新分配给后续标的，使其由「旧逻辑拒绝」翻转为「新逻辑成交」；</li>
 *   <li>整日成交总额永不超过账户可用现金（不超支不变量）。</li>
 * </ol>
 */
class PortfolioBacktestServiceBudgetTest {

    private static final double RATE = 0.0003;
    private static final double CASH = 1000.0;

    @Test
    void rejectedCandidateReallocatesBudgetToExpensiveLaterBuy() {
        DayBudgetGuard guard = new DayBudgetGuard(CASH, 2);

        // 候选 A 被拒（如开盘一字涨停 / 无行情），未花钱 → 仅减少待买数，现金保留。
        guard.onRejected();

        // 候选 B：每股 5 元，一手(100股)成本在「固定均摊 500」与「全额剩余 1000」之间。
        // 旧逻辑 budgetEach = cash/n = 500 时买不起一手（0 手→INSUFFICIENT_CASH_FOR_LOT）；
        // 新逻辑把 A 让渡的额度收进来，budgetEach = 1000/1 = 1000 → 100 股成交。
        double budgetEach = guard.budgetForNext();
        BuyDecision d = PortfolioBacktestService.decideBuy(5.0, RATE, budgetEach, guard.remainingCash());

        assertThat(d.isFill()).as("被拒候选的额度应让渡给后续标的，使其可成交").isTrue();
        assertThat(d.shares()).isEqualTo(100);
        assertThat(d.totalCost()).isEqualTo(505.0);

        // 对照：旧固定均摊额度下同一标的被误拒。
        BuyDecision fixed = PortfolioBacktestService.decideBuy(5.0, RATE, CASH / 2, CASH);
        assertThat(fixed.isFill()).as("固定 cash/n 预算应误拒该标的").isFalse();
        assertThat(fixed.rejectReason()).isEqualTo("INSUFFICIENT_CASH_FOR_LOT");
    }

    @Test
    void dynamicBudgetNeverOverspendsAccountCash() {
        DayBudgetGuard guard = new DayBudgetGuard(CASH, 2);
        double spent = 0.0;

        // 候选 A：每股 3 元，在 500 预算内可买 100 股。
        BuyDecision a = PortfolioBacktestService.decideBuy(3.0, RATE, guard.budgetForNext(), guard.remainingCash());
        assertThat(a.isFill()).isTrue();
        guard.onSpent(a.totalCost());
        spent += a.totalCost();

        // 候选 B：此时剩余现金 695、剩余 1 个，预算=695，可买 100 股。
        BuyDecision b = PortfolioBacktestService.decideBuy(5.0, RATE, guard.budgetForNext(), guard.remainingCash());
        assertThat(b.isFill()).isTrue();
        guard.onSpent(b.totalCost());
        spent += b.totalCost();

        assertThat(spent).as("整日成交总额不得超过账户可用现金").isLessThanOrEqualTo(CASH);
        assertThat(guard.remainingCash()).isEqualTo(CASH - spent);
        assertThat(guard.remaining()).isZero();
    }

    @Test
    void zeroRemainingBudgetIsSafeFallback() {
        DayBudgetGuard guard = new DayBudgetGuard(CASH, 0);
        assertThat(guard.budgetForNext()).isZero();
    }
}
