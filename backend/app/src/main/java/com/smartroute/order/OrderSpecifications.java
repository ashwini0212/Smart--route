package com.smartroute.order;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/** Composable WHERE clauses for the order search endpoint; null filters are skipped. */
final class OrderSpecifications {

    private OrderSpecifications() {
    }

    static Specification<DeliveryOrder> matching(OrderSearch search) {
        return Specification.allOf(
                equal("status", search.status()),
                equal("priority", search.priority()),
                equal("warehouseId", search.warehouseId()),
                createdFrom(search.createdFrom()),
                createdBefore(search.createdTo()));
    }

    private static Specification<DeliveryOrder> equal(String field, Object value) {
        return (root, query, cb) -> value == null ? null : cb.equal(root.get(field), value);
    }

    private static Specification<DeliveryOrder> createdFrom(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    private static Specification<DeliveryOrder> createdBefore(Instant to) {
        return (root, query, cb) -> to == null ? null : cb.lessThan(root.get("createdAt"), to);
    }
}
