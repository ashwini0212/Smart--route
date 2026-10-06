package com.smartroute.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

interface OrderRepository extends JpaRepository<DeliveryOrder, Long>, JpaSpecificationExecutor<DeliveryOrder> {

    Optional<DeliveryOrder> findByCode(String code);

    @Query(value = "SELECT nextval('order_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
