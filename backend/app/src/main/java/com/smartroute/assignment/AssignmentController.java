package com.smartroute.assignment;

import com.smartroute.common.security.Access;
import com.smartroute.common.security.CurrentUser;
import com.smartroute.order.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Assignment", description = "Driver candidates, manual and automatic assignment")
class AssignmentController {

    private final AssignmentService assignments;
    private final CandidateService candidates;
    private final AssignmentConfigService config;
    private final OrderService orders;

    AssignmentController(AssignmentService assignments, CandidateService candidates, AssignmentConfigService config,
                         OrderService orders) {
        this.assignments = assignments;
        this.candidates = candidates;
        this.config = config;
        this.orders = orders;
    }

    @GetMapping("/assignments/candidates")
    @PreAuthorize(Access.STAFF)
    @Operation(summary = "Top-k drivers for an order, with score breakdown and why others were excluded")
    CandidateRanking candidates(@RequestParam long orderId, @RequestParam(defaultValue = "5") @Min(1) @Max(20) int k) {
        return candidates.rank(orders.assignmentView(orderId), k);
    }

    @PostMapping("/assignments")
    @PreAuthorize(Access.STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a waiting order to a driver (all capacity and vehicle rules are re-checked)")
    AssignmentResponse assign(@Valid @RequestBody AssignRequest request) {
        return assignments.assignManually(request.orderId(), request.driverId(), request.reason(),
                CurrentUser.require().id());
    }

    @PostMapping("/assignments/auto")
    @PreAuthorize(Access.STAFF)
    @Operation(summary = "Greedy auto-dispatch of up to `limit` waiting orders, most urgent first [HEURISTIC]")
    AutoDispatchResult auto(@RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
        return assignments.autoDispatch(limit, CurrentUser.require().id());
    }

    @GetMapping("/assignments")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Assignment decisions recorded for an order, oldest first")
    List<AssignmentResponse> forOrder(@RequestParam long orderId) {
        return assignments.forOrder(orderId);
    }

    // Under /api/admin/, which SecurityConfig gates on ADMIN: the annotation said STAFF, so a dispatcher was
    // refused by the URL rule anyway. Both now say the same thing, and a test holds them together.
    @GetMapping("/admin/assignment-config")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Current scoring weights and limits")
    AssignmentSettings getConfig() {
        return config.current();
    }

    @PutMapping("/admin/assignment-config")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Change scoring weights and limits (takes effect for the next ranking)")
    AssignmentSettings updateConfig(@Valid @RequestBody AssignmentConfigRequest request) {
        return config.update(request);
    }
}
