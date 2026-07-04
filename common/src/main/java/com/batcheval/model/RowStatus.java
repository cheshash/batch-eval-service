package com.batcheval.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Per-row evaluation outcome inside a completed job. */
public enum RowStatus {
    SUCCESS("success"),
    FAILED("failed");

    private final String wire;

    RowStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RowStatus fromWire(String value) {
        for (RowStatus status : values()) {
            if (status.wire.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown row status: " + value);
    }
}
