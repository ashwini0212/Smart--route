package com.smartroute.assistant;

import java.util.Map;

/**
 * One thing the assistant may do. Every tool in this package reads; none of them writes.
 *
 * <p>That is enforced in three places rather than trusted: the tools call read methods of the existing
 * services, the architecture test forbids this package from touching a repository directly, and the system
 * prompt tells the model it cannot act. The first two are the real guarantees; the prompt only stops it from
 * promising the user something it cannot do.
 */
public interface AssistantTool {

    /** Snake-case name the model calls. */
    String name();

    /** When to use it, and what it returns. This is prompt text: the model picks tools from it. */
    String description();

    /** JSON Schema for the arguments, as a plain map. */
    Map<String, Object> inputSchema();

    /**
     * Runs the tool and returns JSON for the model to read.
     *
     * @throws ToolArgumentException when the model's arguments do not make sense; the loop hands the message
     *                               back as a failed tool result so the model can correct itself
     */
    String call(ToolArgs args);

    /** Convenience for building a schema with no arguments. */
    static Map<String, Object> noArguments() {
        return Map.of("type", "object", "properties", Map.of());
    }

    /** Convenience for {@code {"type": "object", "properties": {...}, "required": [...]}}. */
    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", java.util.List.of(required));
    }

    /** One property: {@code {"type": type, "description": description}}. */
    static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
