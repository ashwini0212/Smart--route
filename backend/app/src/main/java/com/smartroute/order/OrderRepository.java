package com.smartroute.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface OrderRepository extends JpaRepository<DeliveryOrder, Long>, JpaSpecificationExecutor<DeliveryOrder> {

    Optional<DeliveryOrder> findByCode(String code);

    /** {@code SELECT ... FOR UPDATE}: two dispatchers assigning the same order are serialized here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DeliveryOrder o WHERE o.id = :id")
    Optional<DeliveryOrder> findByIdForUpdate(@Param("id") long id);

    List<DeliveryOrder> findByStatus(OrderStatus status, Pageable pageable);

    List<DeliveryOrder> findByDriverIdAndStatusInOrderByAssignedAtAscIdAsc(long driverId, Collection<OrderStatus> statuses);

    /** Drivers that hold at least one order in one of these statuses; the tracking sweep's only read. */
    @Query("SELECT DISTINCT o.driverId FROM DeliveryOrder o WHERE o.driverId IS NOT NULL AND o.status IN :statuses"
            + " ORDER BY o.driverId")
    List<Long> findDriverIdsWithActiveOrders(@Param("statuses") Collection<OrderStatus> statuses);

    @Query(value = "SELECT nextval('order_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
