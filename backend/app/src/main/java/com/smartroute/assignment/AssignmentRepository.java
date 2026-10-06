package com.smartroute.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByOrderIdOrderByCreatedAtAscIdAsc(long orderId);
}
