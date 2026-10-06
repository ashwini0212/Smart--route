package com.smartroute.events;

import com.smartroute.common.security.Access;
import com.smartroute.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@Validated
@Tag(name = "Events", description = "The recorded domain event stream")
class EventController {

    private final EventStreamService service;

    EventController(EventStreamService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Recorded events, newest first; filter by type or by what they are about")
    PageResponse<SystemEventResponse> search(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Sort newestFirst = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
        return PageResponse.of(service.search(eventType, aggregateType, aggregateId,
                PageRequest.of(page, size, newestFirst)), e -> e);
    }

    @GetMapping("/outbox")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "How many events are waiting to be published (a growing number means the relay is stuck)")
    EventStreamService.OutboxStatus outbox() {
        return service.outboxStatus();
    }
}
