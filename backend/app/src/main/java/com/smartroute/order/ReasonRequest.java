package com.smartroute.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A free-text reason, stored in the order's status history. */
public record ReasonRequest(@NotBlank @Size(max = 255) String reason) {
}
