-- MP-025: market cap provenance on market_data (optional metadata; values may be null for legacy rows)
ALTER TABLE market_data
    ADD COLUMN market_cap_type VARCHAR(40) NULL COMMENT 'TOTAL|FLOAT|FLOAT_CAP_APPROX' AFTER total_market_value,
    ADD COLUMN market_cap_source VARCHAR(60) NULL COMMENT 'e.g. amount_div_turnover|official' AFTER market_cap_type,
    ADD COLUMN market_cap_reliability VARCHAR(40) NULL COMMENT 'OFFICIAL|FLOAT_CAP_APPROX|UNKNOWN_LEGACY' AFTER market_cap_source;
