package com.batcheval.business;

import java.util.List;

/** Business-rule validation failures (mapped to HTTP by the activity layer). */
public class BusinessValidationException extends Exception {

    private final List<String> details;

    public BusinessValidationException(String message) {
        super(message);
        this.details = List.of();
    }

    public BusinessValidationException(String message, List<String> details) {
        super(message);
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
