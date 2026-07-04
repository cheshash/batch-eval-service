package com.batcheval.business;

import com.batcheval.accessor.S3Accessor;
import com.batcheval.config.AppConfig;
import com.batcheval.dao.JobDao;
import com.batcheval.dao.JobDao.BatchJobRecord;
import com.batcheval.model.BatchInputLine;
import com.batcheval.model.BatchJobResponse;
import com.batcheval.model.JobStatus;
import com.batcheval.model.ModelPriority;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Business layer — batch submit, status, download. */
public class BatchIngestBusiness {

    private final AppConfig config;
    private final JobDao jobDao;
    private final S3Accessor s3Accessor;

    public BatchIngestBusiness(AppConfig config, JobDao jobDao, S3Accessor s3Accessor) {
        this.config = config;
        this.jobDao = jobDao;
        this.s3Accessor = s3Accessor;
    }

    /**
     * Persist job in SQL, upload input to S3. S3 object-created event notifies SQS — API does not enqueue.
     */
    public BatchJobRecord submitBatch(String fileName, byte[] content) throws Exception {
        validateFileName(fileName);
        if (content.length > config.maxFileSizeBytes()) {
            throw new BusinessValidationException("file exceeds max size of " + config.maxFileSizeBytes() + " bytes");
        }
        boolean highPriority = validateJsonlContent(content);

        UUID fileId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String s3Key = s3Accessor.inputKey(fileId, highPriority);
        Instant expiresAt = Instant.now().plus(config.resultRetentionDays(), ChronoUnit.DAYS);

        BatchJobRecord job = jobDao.createSubmission(
                fileId, jobId, fileName, s3Key, content.length, highPriority, expiresAt);
        try {
            s3Accessor.uploadBytes(content, s3Key);
        } catch (Exception ex) {
            jobDao.deleteSubmission(fileId, jobId);
            throw new BusinessValidationException("failed to upload input file to storage: " + ex.getMessage());
        }
        return job;
    }

    public BatchJobResponse getJobStatus(UUID jobId) throws Exception {
        return toJobStatus(jobDao.getJob(jobId)
                .orElseThrow(() -> new BusinessValidationException("job_id " + jobId + " not found")));
    }

    public Map<String, Object> getResultsDownload(UUID jobId) throws Exception {
        BatchJobRecord job = jobDao.getJob(jobId)
                .orElseThrow(() -> new BusinessValidationException("job_id " + jobId + " not found"));

        if (job.status() != JobStatus.COMPLETED) {
            throw new BusinessValidationException("results are only available when job status is completed");
        }
        if (job.downloadConsumed()) {
            throw new BusinessValidationException("download link has already been consumed");
        }
        if (job.resultS3Key() == null) {
            throw new BusinessValidationException("result file not found");
        }

        String url = s3Accessor.presignedGet(job.resultS3Key());
        jobDao.markDownloadConsumed(jobId);
        Instant expiresAt = Instant.now().plus(config.downloadUrlTtl());
        return com.batcheval.builder.ApiResponseBuilder.resultsDownload(url, expiresAt);
    }

    public BatchJobResponse toJobStatus(BatchJobRecord job) {
        return com.batcheval.builder.ApiResponseBuilder.jobStatus(job);
    }

    private void validateFileName(String fileName) throws BusinessValidationException {
        if (fileName == null || !fileName.toLowerCase().endsWith(".jsonl")) {
            throw new BusinessValidationException("file must be a .jsonl file");
        }
    }

    private boolean validateJsonlContent(byte[] content) throws Exception {
        Set<String> seenIds = new HashSet<>();
        List<String> errors = new ArrayList<>();
        int[] count = {0};
        boolean[] hasPriorityModel = {false};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                count[0]++;
                if (count[0] > config.maxRequestsPerFile()) {
                    throw new BusinessValidationException(
                            "file exceeds max of " + config.maxRequestsPerFile() + " requests");
                }
                try {
                    BatchInputLine row = parseInputLine(line, count[0]);
                    if (ModelPriority.isPriorityModel(row.model(), config.priorityModel())) {
                        hasPriorityModel[0] = true;
                    }
                    if (!seenIds.add(row.requestId())) {
                        errors.add("line " + count[0] + ": duplicate request_id '" + row.requestId() + "'");
                    }
                } catch (BusinessValidationException ex) {
                    errors.add("line " + count[0] + ": " + ex.getMessage());
                    if (errors.size() >= 10) {
                        throw new BusinessValidationException("input file validation failed", errors);
                    }
                }
            }
        }

        if (count[0] == 0) {
            throw new BusinessValidationException("file is empty");
        }
        if (!errors.isEmpty()) {
            throw new BusinessValidationException("input file validation failed", errors);
        }
        return hasPriorityModel[0];
    }

    private static BatchInputLine parseInputLine(String line, int lineNo) throws BusinessValidationException {
        try {
            return BatchInputLine.parseJson(line);
        } catch (IllegalArgumentException ex) {
            throw new BusinessValidationException("line " + lineNo + ": " + ex.getMessage());
        }
    }
}
