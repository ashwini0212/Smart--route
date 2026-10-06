package com.smartroute.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderStatusChangeRepository extends JpaRepository<OrderStatusChange, Long> {

    List<OrderStatusChange> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);
}
