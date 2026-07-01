package com.quant.service.scoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalIndicatorsTest {

    @Test
    void simpleMovingAverageUsesRequestedWindow() {
        double[] values = {1, 2, 3, 4, 5};

        assertThat(TechnicalIndicators.smaAt(values, 4, 3)).isEqualTo(4.0);
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
