package com.quant.service.scoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalIndicatorsTest {

    @Test
    void simpleMovingAverageUsesRequestedWindow() {
        double[] values = {1, 2, 3, 4, 5};

        assertThat(TechnicalIndicators.smaAt(values, 4, 3)).isEqualTo(4.0);
    }

    /** R20-P1 回归：数组 sma 的 warmup 区间须返回 NaN（与 smaAt/Python 口径一致），此前返回原始价。 */
    @Test
    void arraySmaWarmupReturnsNanNotRawValue() {
        double[] values = {1, 2, 3, 4, 5};
        double[] ma3 = TechnicalIndicators.sma(values, 3);

        assertThat(ma3[0]).isNaN();
        assertThat(ma3[1]).isNaN();
        assertThat(ma3[2]).isEqualTo(2.0);
        assertThat(ma3[4]).isEqualTo(4.0);
    }

    @Test
    void risingSeriesProducesHighRsi() {
        double[] close = new double[30];
        for (int i = 0; i < close.length; i++) {
            close[i] = 10 + i;
        }

        assertThat(TechnicalIndicators.rsiAt(close, close.length - 1, 14)).isGreaterThan(90);
    }

    @Test
    void rsiAtMatchesWilderRsiLine() {
        double[] close = new double[40];
        for (int i = 0; i < close.length; i++) {
            close[i] = 100 + Math.sin(i / 4.0) * 8 + i * 0.1;
        }
        int period = 14;
        double[] rsiLine = TechnicalIndicators.rsi(close, period);
        int index = close.length - 1;

        assertThat(TechnicalIndicators.rsiAt(close, index, period)).isEqualTo(rsiLine[index]);
    }
}
