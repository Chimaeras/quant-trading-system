package com.quant.repository;

import com.quant.entity.SelectorTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SelectorTemplateRepository extends JpaRepository<SelectorTemplate, Long> {
    Optional<SelectorTemplate> findByTemplateId(String templateId);
}
