package com.batcheval.builder;

import com.batcheval.dao.JobDao.BatchJobRecord;
import com.batcheval.model.BatchJobResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Builder — API response payloads. */
public final class ApiResponseBuilder {

    private ApiResponseBuilder() {}

    public static Map<String, Object> resultsDownload(String downloadUrl, Instant expiresAt) {
        return Map.of(
                "download_url", downloadUrl,
                "expires_at", expiresAt,
                "content_type", "application/x-ndjson"
        );
    }

    public static BatchJobResponse jobStatus(BatchJobRecord job) {
        return BatchJobResponse.of(
                job.jobId(),
                job.fileId(),
                job.status(),
                job.createdAt(),
                job.startedAt(),
                job.completedAt(),
                job.failedAt(),
                job.total(),
                job.completed(),
                job.failed(),
                job.errorMessage()
        );
    }
}
