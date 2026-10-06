package com.smartroute.assistant;

/**
 * A tool was called with arguments that do not make sense. Thrown by {@link ToolArgs} and by the tools
 * themselves; the loop reports the message to the model as a failed tool result so it can try again, and
 * nothing about it reaches the HTTP response.
 */
public class ToolArgumentException extends IllegalArgumentException {

    public ToolArgumentException(String message) {
        super(message);
    }
}
