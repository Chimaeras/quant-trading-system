package com.quant.repository;

import com.quant.entity.PortfolioOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioOrderRepository extends JpaRepository<PortfolioOrder, Long> {

    /** 按 seq 升序分页（长区间订单不分页会打爆响应，V2-MP-020）。 */
    Page<PortfolioOrder> findByRunIdOrderBySeqAsc(String runId, Pageable pageable);

    long countByRunId(String runId);
}
