package com.quant.repository;

import com.quant.entity.T1Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface T1CandidateRepository extends JpaRepository<T1Candidate, Long> {

    Optional<T1Candidate> findByTradeDate(LocalDate tradeDate);

    List<T1Candidate> findTop30ByOrderByTradeDateDesc();

    /** 已核销的候选（按日升序），用于跨日策略绩效聚合。 */
    List<T1Candidate> findByTradeDateBetweenAndSettledTrueOrderByTradeDateAsc(LocalDate start, LocalDate end);
}
