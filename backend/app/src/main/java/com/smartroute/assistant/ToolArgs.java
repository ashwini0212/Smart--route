package com.smartroute.assistant;

import java.util.Map;
import java.util.Optional;

/**
 * The arguments of one tool call, read defensively.
 *
 * <p>A model's arguments are untrusted input in the ordinary sense — wrong types, missing fields, numbers out
 * of range — so every accessor either returns a value within the stated bounds or throws
 * {@link ToolArgumentException}, which the loop turns into a failed tool result the model can read and retry.
 * The model never sees a stack trace, and a bad argument never reaches a service.
 */
public record ToolArgs(Map<String, Object> values) {

    public static ToolArgs of(Map<String, Object> values) {
        return new ToolArgs(values == null ? Map.of() : values);
    }

    public Optional<String> text(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    public String requiredText(String key) {
        return text(key).orElseThrow(() -> new ToolArgumentException(key + " is required"));
    }

    public Optional<Long> number(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        try {
            return Optional.of((long) Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new ToolArgumentException(key + " must be a number, got: " + value);
        }
    }

    /** An integer clamped to a range, with a default when absent. Out of range is an error, not a clamp. */
    public int bounded(String key, int fallback, int min, int max) {
        long value = number(key).orElse((long) fallback);
        if (value < min || value > max) {
            throw new ToolArgumentException(key + " must be between " + min + " and " + max + ", got " + value);
        }
        return (int) value;
    }

    public Optional<Double> decimal(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        try {
            return Optional.of(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new ToolArgumentException(key + " must be a number, got: " + value);
        }
    }

    /** Reads an enum by name, case-insensitively, and lists the valid names when it is wrong. */
    public <E extends Enum<E>> Optional<E> enumeration(String key, Class<E> type) {
        Optional<String> raw = text(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(raw.get())) {
                return Optional.of(candidate);
            }
        }
        StringBuilder names = new StringBuilder();
        for (E candidate : type.getEnumConstants()) {
            names.append(names.isEmpty() ? "" : ", ").append(candidate.name());
        }
        throw new ToolArgumentException(key + " must be one of: " + names + ", got: " + raw.get());
    }
}
