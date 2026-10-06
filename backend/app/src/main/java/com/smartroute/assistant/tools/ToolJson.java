package com.smartroute.assistant.tools;

import tools.jackson.databind.ObjectMapper;

/**
 * Turns a tool's result into the JSON the model reads.
 *
 * <p>Tools return JSON rather than prose on purpose: a number inside a named field is harder to misread than
 * the same number inside a sentence, and the field name travels with it into the answer.
 */
final class ToolJson {

    private final ObjectMapper mapper;

    ToolJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String of(Object value) {
        return mapper.writeValueAsString(value);
    }
}
