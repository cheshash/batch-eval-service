package com.batcheval.business;

import com.batcheval.model.BatchInputLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchIngestBusinessInputTest {

    @Test
    void batchInputLineAcceptsValidRow() {
        BatchInputLine row = BatchInputLine.parseJson("""
                {"request_id":"r1","model":"meta-llama-3-8b-instruct","prompt":"hello","metadata":{"k":"v"}}
                """);
        assertThat(row.requestId()).isEqualTo("r1");
        assertThat(row.model()).isEqualTo("meta-llama-3-8b-instruct");
        assertThat(row.prompt()).isEqualTo("hello");
        assertThat(row.metadata()).containsEntry("k", "v");
    }

    @Test
    void batchInputLineRequiresRequestIdPromptAndModel() {
        assertThatThrownBy(() -> BatchInputLine.parseJson("{\"request_id\":\"r1\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchInputLine.parseJson("{\"prompt\":\"hello\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchInputLine.parseJson("{\"request_id\":\"r1\",\"prompt\":\"hello\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
