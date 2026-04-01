package com.quant.repository;

import com.quant.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderId(String orderId);

    List<Order> findByAccountIdOrderByCreateTimeDesc(Long accountId, org.springframework.data.domain.Pageable pageable);
}
