package com.smartroute.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One question.
 *
 * <p>The length limit is both a cost control and a prompt-injection reducer: the longer the free text, the
 * more room there is for instructions aimed at the model rather than questions aimed at the data. It cannot
 * remove that risk — nothing here can — which is why the model has no tool that writes.
 */
public record AssistantAskRequest(
        @NotBlank @Size(max = 1000) String question) {
}
