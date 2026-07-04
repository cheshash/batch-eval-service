package com.batcheval.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RowErrorCode {
    RATE_LIMIT_EXHAUSTED("rate_limit_exhausted"),
    CLIENT_ERROR("client_error"),
    SERVER_ERROR("server_error"),
    CONNECTION_ERROR("connection_error"),
    TIMEOUT("timeout"),
    INTERRUPTED("interrupted"),
    INTERNAL_ERROR("internal_error");

    private final String wire;

    RowErrorCode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RowErrorCode fromWire(String value) {
        for (RowErrorCode code : values()) {
            if (code.wire.equals(value) || code.name().equalsIgnoreCase(value)) {
                return code;
            }
        }
        throw new IllegalArgumentException("unknown row error code: " + value);
    }
}
