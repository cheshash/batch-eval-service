package com.batcheval.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Job lifecycle. Terminal states: {@code completed}, {@code failed}.
 * Row-level failures still end in {@code completed} with failed metrics.
 */
public enum JobStatus {
    QUEUED("queued"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String wire;

    JobStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static JobStatus fromWire(String value) {
        for (JobStatus status : values()) {
            if (status.wire.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown job status: " + value);
    }
}
