package com.quant.repository;

import com.quant.entity.PolicyIndustryDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PolicyIndustryDailyRepository extends JpaRepository<PolicyIndustryDaily, Long> {

    void deleteByTradeDate(LocalDate tradeDate);

    @Query("SELECT p FROM PolicyIndustryDaily p WHERE p.industry = :ind AND p.tradeDate <= :d ORDER BY p.tradeDate DESC")
    List<PolicyIndustryDaily> findLatestForIndustry(@Param("ind") String industry, @Param("d") LocalDate d, org.springframework.data.domain.Pageable pageable);

    default Optional<Double> findScoreForIndustryOnOrBefore(String industry, LocalDate d) {
        if (industry == null || industry.isBlank()) return Optional.empty();
        List<PolicyIndustryDaily> list = findLatestForIndustry(industry, d, org.springframework.data.domain.PageRequest.of(0, 1));
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0).getScore());
    }
}
