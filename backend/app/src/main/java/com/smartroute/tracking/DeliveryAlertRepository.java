package com.smartroute.tracking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface DeliveryAlertRepository extends JpaRepository<DeliveryAlert, Long> {

    List<DeliveryAlert> findByOrderIdIn(Collection<Long> orderIds);

    void deleteByOrderIdIn(Collection<Long> orderIds);
}
