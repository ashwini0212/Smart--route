package com.smartroute.assistant;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;

/**
 * The model API could not be reached, or refused the request. 502: the failure is upstream of this service.
 * The provider's own message is logged, never returned — it can carry request details a user should not see.
 */
public class AssistantFailedException extends ApiException {

    public AssistantFailedException(String message, Throwable cause) {
        super(ErrorCode.UPSTREAM_FAILED, message);
        initCause(cause);
    }
}
