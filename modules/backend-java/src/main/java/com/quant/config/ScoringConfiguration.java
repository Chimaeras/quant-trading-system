package com.quant.config;

import com.quant.service.scoring.ScoringPropertiesBridge;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ScoringProperties.class)
public class ScoringConfiguration {

    @Bean
    ScoringPropertiesBridge scoringPropertiesBridge(ScoringProperties properties) {
        return new ScoringPropertiesBridge(properties);
    }
}
