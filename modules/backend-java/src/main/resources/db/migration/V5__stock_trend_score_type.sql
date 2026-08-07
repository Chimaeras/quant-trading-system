-- MP-017: split trend vs comprehensive persistence identity via score_type
ALTER TABLE stock_trend_score ADD COLUMN score_type VARCHAR(32) NULL AFTER factor_version;

UPDATE stock_trend_score SET score_type = 'TREND' WHERE score_type IS NULL;

ALTER TABLE stock_trend_score MODIFY score_type VARCHAR(32) NOT NULL;

ALTER TABLE stock_trend_score DROP INDEX uk_sts_code_date;

ALTER TABLE stock_trend_score
    ADD UNIQUE KEY uk_sts_code_date_type_fv (stock_code, exchange_type, trade_date, score_type, factor_version);

CREATE INDEX idx_sts_trade_date_type ON stock_trend_score (trade_date, score_type);
