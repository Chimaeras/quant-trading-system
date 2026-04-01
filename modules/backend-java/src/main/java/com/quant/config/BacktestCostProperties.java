package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "backtest.cost")
public class BacktestCostProperties {
    /** 佣金比例，例如 0.0003 表示万3 */
    private double commissionRate = 0.0003;
    /** 最低佣金 */
    private double minCommission = 5.0;
    /** 印花税比例（仅卖出） */
    private double stampDutyRate = 0.001;
    /** 过户费比例（双边） */
    private double transferFeeRate = 0.00002;
    /** 滑点（bps），例如 2 表示万2 */
    private double slippageBps = 2.0;
}
