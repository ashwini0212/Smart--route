package com.smartroute.assistant;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;

/**
 * The assistant was asked something while it is not configured. 503 with the reason, so an operator reading
 * the response knows which setting is missing without reading the logs.
 */
public class AssistantUnavailableException extends ApiException {

    public AssistantUnavailableException(String message) {
        super(ErrorCode.FEATURE_NOT_CONFIGURED, message);
    }
}
