package com.smartroute.assistant;

import com.smartroute.assistant.AssistantResponses.Answer;
import com.smartroute.assistant.AssistantResponses.Status;
import com.smartroute.common.security.Access;
import com.smartroute.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant (FR-24), optional and read-only.
 *
 * <p>Staff only. A viewer can read every number the assistant can, but the assistant costs money per question,
 * and a role that exists to look at dashboards does not need a paid endpoint. Drivers are excluded for the
 * ordinary reason: these are fleet-wide answers.
 *
 * <p>Asking is a POST because the question goes in the body, not because anything changes. Nothing in this
 * package writes.
 */
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "Assistant", description = "Optional read-only assistant; answers from tool calls, or says it cannot")
class AssistantController {

    private final AssistantService assistant;
    private final AssistantLimiter limiter;

    AssistantController(AssistantService assistant, AssistantLimiter limiter) {
        this.assistant = assistant;
        this.limiter = limiter;
    }

    @GetMapping("/status")
    @PreAuthorize(Access.STAFF)
    @Operation(summary = "Whether the assistant is configured, and which tools it has")
    Status status() {
        return assistant.status();
    }

    @PostMapping("/ask")
    @PreAuthorize(Access.STAFF)
    @Operation(summary = "Ask a question about current operations; the answer separates facts from suggestions")
    Answer ask(@Valid @RequestBody AssistantAskRequest request) {
        limiter.check(CurrentUser.require().id());
        return assistant.ask(request.question());
    }
}
