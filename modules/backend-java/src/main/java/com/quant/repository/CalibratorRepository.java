package com.quant.repository;

import com.quant.entity.Calibrator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CalibratorRepository extends JpaRepository<Calibrator, Long> {

    Optional<Calibrator> findByCalibratorVersion(String calibratorVersion);

    /** champion 查找：策略 + 持有期 + 指定 base model（原子绑定，MP-004）。 */
    Optional<Calibrator> findFirstByStrategyTypeAndHorizonAndBaseModelVersionAndStageOrderByCreatedAtDesc(
            String strategyType, Integer horizon, String baseModelVersion, String stage);

    /** 晋级/回滚：base model 绑定的最近非 archived 校准器（challenger/champion 均可，幂等；V2-MP-018）。 */
    Optional<Calibrator> findFirstByStrategyTypeAndHorizonAndBaseModelVersionAndStageInOrderByCreatedAtDesc(
            String strategyType, Integer horizon, String baseModelVersion, java.util.List<String> stages);

    /** 最近注册的校准器（供 UI / 审计）。 */
    List<Calibrator> findTop20ByOrderByCreatedAtDesc();
}
