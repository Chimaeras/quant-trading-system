package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_info", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_code", "exchange_type"})
})
public class StockInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 50)
    private String stockName;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 50)
    private String exchangeTypeName;

    @Column(length = 50)
    private String industry;

    @Column(length = 50)
    private String sector;

    private LocalDate listDate;

    @JdbcTypeCode(SqlTypes.TINYINT)
    private Integer status = 1;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
