package com.quant.repository;

import com.quant.entity.ScreenerTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScreenerTemplateRepository extends JpaRepository<ScreenerTemplate, Long> {

    List<ScreenerTemplate> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ScreenerTemplate> findByUserIdAndName(Long userId, String name);

    long deleteByUserIdAndId(Long userId, Long id);
}
