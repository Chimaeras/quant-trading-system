package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Data
@Entity
@Table(name = "selector_result_item")
public class SelectorResultItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String resultId;

    @Column(nullable = false)
    private Integer rankNo;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    @Column(nullable = false)
    private Double finalScore;

    @Column(nullable = false)
    private Double trendScore;

    @Column(nullable = false)
    private Double momentumScore;

    @Column(nullable = false)
    private Double volumeScore;

    @Column(nullable = false)
    private Double riskPenalty;

    @Column(nullable = false)
    private Double ret20;

    @Column(nullable = false)
    private Double ret60;

    @Column(nullable = false)
    private Double volatility20;

    @Column(nullable = false)
    private Double maxDrawdown60;

    @Column(nullable = false)
    private Double avgAmount20;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> reasons;
}
