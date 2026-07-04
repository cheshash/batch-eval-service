package com.batcheval.model;

import com.batcheval.util.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModelTest {

    @Test
    void jobStatusUsesSnakeCaseWireFormat() throws Exception {
        assertThat(Json.mapper().writeValueAsString(JobStatus.IN_PROGRESS)).isEqualTo("\"in_progress\"");
        assertThat(JobStatus.fromWire("completed")).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void batchInputLineValidatesRequestIdPattern() {
        assertThatThrownBy(() -> new BatchInputLine("bad id!", "meta-llama-3-8b-instruct", "prompt", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request_id");
    }

    @Test
    void batchInputLineRejectsUnknownJsonFields() {
        assertThatThrownBy(() -> BatchInputLine.parseJson("{\"request_id\":\"r1\",\"model\":\"m1\",\"prompt\":\"hi\",\"extra\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void batchInputLineDeserializesFromJsonl() {
        BatchInputLine row = BatchInputLine.parseJson(
                "{\"request_id\":\"r1\",\"model\":\"meta-llama-3-8b-instruct\",\"prompt\":\"hello\",\"metadata\":{\"k\":\"v\"}}"
        );
        assertThat(row.requestId()).isEqualTo("r1");
        assertThat(row.model()).isEqualTo("meta-llama-3-8b-instruct");
        assertThat(row.prompt()).isEqualTo("hello");
        assertThat(row.metadata()).containsEntry("k", "v");
    }

    @Test
    void batchOutputLineSuccessFactory() throws Exception {
        BatchOutputLine line = BatchOutputLine.success("r1", Map.of("output", "ok"), 42L);
        String json = Json.mapper().writeValueAsString(line);
        assertThat(json).contains("\"status\":\"success\"");
        assertThat(json).contains("\"latency_ms\":42");
        assertThat(json).doesNotContain("error");
    }

    @Test
    void batchOutputLineRejectsSuccessWithError() {
        assertThatThrownBy(() -> new BatchOutputLine(
                "r1",
                RowStatus.SUCCESS,
                Map.of("x", 1),
                BatchOutputLine.RowError.of(RowErrorCode.CLIENT_ERROR, "bad", 400, 1),
                1L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchOutputLineFailedFactory() throws Exception {
        BatchOutputLine line = BatchOutputLine.failed(
                "r1",
                BatchOutputLine.RowError.of(RowErrorCode.CLIENT_ERROR, "bad", 400, 1)
        );
        String json = Json.mapper().writeValueAsString(line);
        assertThat(json).contains("\"status\":\"failed\"");
        assertThat(json).contains("\"code\":\"client_error\"");
        assertThat(json).doesNotContain("response");
    }

    @Test
    void batchJobResponseValidatesCompletedMetrics() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> BatchJobResponse.of(
                id, id, JobStatus.COMPLETED, now, now, now, null,
                10, 5, 3, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchJobResponseQueuedShape() throws Exception {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        BatchJobResponse response = BatchJobResponse.of(
                id, id, JobStatus.QUEUED, created, null, null, null, 0, 0, 0, null
        );
        String json = Json.mapper().writeValueAsString(response);
        assertThat(json).contains("\"status\":\"queued\"");
        assertThat(json).doesNotContain("started_at");
    }

    @Test
    void batchJobResponseFailedRequiresErrorMessage() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> BatchJobResponse.of(
                id, id, JobStatus.FAILED, now, null, null, now, 0, 0, 0, null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
