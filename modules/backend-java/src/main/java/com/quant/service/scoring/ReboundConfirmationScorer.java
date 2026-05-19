package com.quant.service.scoring;

import com.quant.entity.MarketData;

import java.util.ArrayList;
import java.util.List;

/**
 * 超跌反弹确认信号：底背离、量能确认、K 线反转形态。
 */
public final class ReboundConfirmationScorer {

    private ReboundConfirmationScorer() {}

    public record ConfirmationResult(int score, boolean hasSignal, String summary) {}

    public static ConfirmationResult score(List<MarketData> bars) {
        if (bars == null || bars.size() < 35) {
            return new ConfirmationResult(0, false, "数据不足");
        }

        List<Double> close = new ArrayList<>();
        List<Double> open = new ArrayList<>();
        List<Double> high = new ArrayList<>();
        List<Double> low = new ArrayList<>();
        List<Double> amount = new ArrayList<>();

        for (MarketData b : bars) {
            if (b.getClosePrice() == null || b.getClosePrice().doubleValue() <= 0) continue;
            double c = b.getClosePrice().doubleValue();
            close.add(c);
            open.add(b.getOpenPrice() != null ? b.getOpenPrice().doubleValue() : c);
            high.add(b.getHighPrice() != null ? b.getHighPrice().doubleValue() : c);
            low.add(b.getLowPrice() != null ? b.getLowPrice().doubleValue() : c);
            amount.add(b.getAmount() != null ? b.getAmount().doubleValue() : 0d);
        }
        if (close.size() < 35) {
            return new ConfirmationResult(0, false, "数据不足");
        }

        int pts = 0;
        List<String> parts = new ArrayList<>();

        if (macdBottomDivergence(close)) {
            pts += 6;
            parts.add("MACD底背离");
        }
        if (volumeConfirmation(amount)) {
            pts += 5;
            parts.add("量能确认");
        }
        if (hammerOrMorningStar(open, high, low, close)) {
            pts += 4;
            parts.add("反转K线");
        }

        pts = (int) ScoringUtils.clamp(pts, 0, 15);
        boolean has = pts >= 5;
        String summary = parts.isEmpty() ? "无确认信号" : String.join("+", parts);
        return new ConfirmationResult(pts, has, summary);
    }

    private static boolean macdBottomDivergence(List<Double> close) {
        int n = close.size();
        if (n < 35) return false;
        double[] arr = close.stream().mapToDouble(Double::doubleValue).toArray();
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(arr);

        int lookback = Math.min(20, n - 5);
        // 在 lookback 窗口内寻找两个显著的局部低点（价格谷底）
        int firstTrough = -1, secondTrough = -1;
        for (int i = n - lookback + 2; i < n - 5; i++) {
            double prev = close.get(i - 1);
            double curr = close.get(i);
            double next = close.get(i + 1);
            double next2 = close.get(i + 2);
            // 局部低点：当前价格低于前后各 2 根 K 线
            if (curr <= prev && curr <= next && curr <= next2
                    && curr <= close.get(i - 2)) {
                if (secondTrough < 0) {
                    secondTrough = i;
                } else if (firstTrough < 0) {
                    firstTrough = secondTrough;
                    secondTrough = i;
                } else {
                    firstTrough = secondTrough;
                    secondTrough = i;
                }
            }
        }
        // 需要至少两个低点来比较
        if (firstTrough < 0 || secondTrough < 0) return false;

        // 底背离：价格新低（secondTrough < firstTrough）但 MACD 柱未创新低
        boolean priceLowerLow = close.get(secondTrough) <= close.get(firstTrough) * 1.02;
        boolean macdHigherLow = macd.macd()[secondTrough] > macd.macd()[firstTrough];
        // 当前价格接近最新的低点（在反弹起始位置）
        boolean nearRecentLow = Math.abs(close.get(n - 1) - close.get(secondTrough))
                / Math.max(1e-9, close.get(secondTrough)) < 0.03;

        return priceLowerLow && macdHigherLow && nearRecentLow;
    }

    private static boolean volumeConfirmation(List<Double> amount) {
        int n = amount.size();
        if (n < 13) return false;
        double recent3 = TechnicalIndicators.avg(amount, n - 3, n - 1);
        double prev10 = TechnicalIndicators.avg(amount, n - 13, n - 4);
        return prev10 > 0 && recent3 > prev10 * 1.2;
    }

    private static boolean hammerOrMorningStar(
            List<Double> open, List<Double> high, List<Double> low, List<Double> close) {
        int n = close.size();
        if (n < 3) return false;

        double o = open.get(n - 1);
        double h = high.get(n - 1);
        double l = low.get(n - 1);
        double c = close.get(n - 1);
        double body = Math.abs(c - o);
        double range = h - l;
        if (range < 1e-9) return false;

        double lowerShadow = Math.min(o, c) - l;
        boolean hammer = lowerShadow >= body * 2 && body / range < 0.35;

        double o2 = open.get(n - 2);
        double c2 = close.get(n - 2);
        double o3 = open.get(n - 3);
        double c3 = close.get(n - 3);
        boolean morningStar = c3 < o3 && Math.abs(c2 - o2) / Math.max(1e-9, c2) < 0.01 && c > o
                && c > (o3 + c3) / 2;

        return hammer || morningStar;
    }
}
