package com.quant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分钟线批量导入的单根 bar。
 *
 * <p>字段名刻意与 Python 侧 {@code paper.watch.models.WatchBar} 对齐（{@code open/high/low/close}
 * 而非 {@code open_price}），避免调用方在每次提交前做一次易错的重命名。
 *
 * <p>线上 JSON 键统一为 snake_case：`/api/data` 下的响应本来就是 snake_case
 * （各 Service 用 Map 显式拼键），请求体若走 Jackson 默认的 camelCase 会让同一
 * 前缀下出现两种命名口径。故此处显式标注，而非依赖全局命名策略。
 */
@Data
public class MinuteBarItem {

    @JsonProperty("stock_code")
    private String stockCode;

    /** bar 周期（分钟），如 5。 */
    @JsonProperty("period_min")
    private Integer periodMin;

    @JsonProperty("start_ts")
    private LocalDateTime startTs;

    @JsonProperty("end_ts")
    private LocalDateTime endTs;

    @JsonProperty("trade_date")
    private LocalDate tradeDate;

    private BigDecimal open;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal close;

    /** 成交量，单位股。 */
    private Long volume;

    /** 来源标记，如 eastmoney_batch / eastmoney_kline。 */
    private String source;
}
