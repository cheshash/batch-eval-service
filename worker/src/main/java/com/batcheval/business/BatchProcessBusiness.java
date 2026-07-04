package com.batcheval.business;

import com.batcheval.accessor.S3Accessor;
import com.batcheval.config.AppConfig;
import com.batcheval.dao.JobDao;
import com.batcheval.dao.JobDao.BatchFileRecord;
import com.batcheval.dao.JobDao.BatchJobRecord;
import com.batcheval.model.BatchInputLine;
import com.batcheval.model.BatchOutputLine;
import com.batcheval.model.BatchOutputLine.RowError;
import com.batcheval.model.JobStatus;
import com.batcheval.model.ModelPriority;
import com.batcheval.model.RowErrorCode;
import com.batcheval.model.RowStatus;
import com.batcheval.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Business layer — async batch row processing and result upload. */
public class BatchProcessBusiness {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessBusiness.class);

    private final AppConfig config;
    private final JobDao jobDao;
    private final S3Accessor s3Accessor;
    private final PromptClient promptClient;

    private int lastSuccessCount;
    private int lastFailCount;

    public BatchProcessBusiness(
            AppConfig config,
            JobDao jobDao,
            S3Accessor s3Accessor,
            PromptClient promptClient
    ) {
        this.config = config;
        this.jobDao = jobDao;
        this.s3Accessor = s3Accessor;
        this.promptClient = promptClient;
    }

    public void processJob(UUID jobId) throws Exception {
        BatchJobRecord job = jobDao.getJob(jobId).orElse(null);
        if (job == null) {
            log.error("job {} not found", jobId);
            return;
        }
        if (job.status() == JobStatus.COMPLETED || job.status() == JobStatus.FAILED) {
            log.info("job {} already terminal ({})", jobId, job.status());
            return;
        }

        BatchFileRecord file = jobDao.getFile(job.fileId()).orElse(null);
        if (file == null) {
            jobDao.updateJob(job.withFailed("input file record missing"));
            return;
        }

        job = job.withStatus(JobStatus.IN_PROGRESS).withStarted(Instant.now());
        jobDao.updateJob(job);

        List<BatchInputLine> rows;
        try {
            rows = loadInputRows(file.s3Key());
        } catch (IllegalStateException ex) {
            jobDao.updateJob(job.withFailed(ex.getMessage()));
            return;
        }
        job = job.withTotal(rows.size());
        jobDao.updateJob(job);

        List<BatchInputLine> processingOrder = new ArrayList<>(rows);
        processingOrder.sort(ModelPriority.rowComparator(config.priorityModel()));
        if (job.highPriority()) {
            log.info("job {} is high-priority (contains {})", jobId, config.priorityModel());
        }

        Map<String, String> outputs = processRows(processingOrder);
        List<String> orderedLines = rows.stream().map(row -> outputs.get(row.requestId())).toList();

        String resultKey = s3Accessor.resultKey(jobId);
        s3Accessor.uploadJsonl(resultKey, orderedLines);

        jobDao.updateJob(job.withCompleted(lastSuccessCount, lastFailCount, resultKey));
        log.info("job {} completed: success={} failed={}", jobId, lastSuccessCount, lastFailCount);
    }

    private List<BatchInputLine> loadInputRows(String s3Key) {
        List<BatchInputLine> rows = new ArrayList<>();
        int[] lineNo = {0};
        s3Accessor.forEachLine(s3Key, line -> {
            lineNo[0]++;
            try {
                rows.add(BatchInputLine.parseJson(line));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("line " + lineNo[0] + ": " + ex.getMessage(), ex);
            }
        });
        if (rows.isEmpty()) {
            throw new IllegalStateException("input file is empty");
        }
        return rows;
    }

    private Map<String, String> processRows(List<BatchInputLine> rows) {
        Map<String, String> results = new ConcurrentHashMap<>();
        int[] successCount = {0};
        int[] failCount = {0};
        Semaphore semaphore = new Semaphore(config.workerMaxConcurrency());

        try (ExecutorService executor = Executors.newFixedThreadPool(config.workerMaxConcurrency())) {
            List<CompletableFuture<Void>> futures = rows.stream()
                    .map(row -> CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                            try {
                                BatchOutputLine output = processSingleRow(row);
                                results.put(row.requestId(), Json.mapper().writeValueAsString(output));
                                if (output.status() == RowStatus.SUCCESS) {
                                    successCount[0]++;
                                } else {
                                    failCount[0]++;
                                }
                            } finally {
                                semaphore.release();
                            }
                        } catch (Exception ex) {
                            failCount[0]++;
                            BatchOutputLine output = BatchOutputLine.failed(
                                    row.requestId(),
                                    RowError.of(RowErrorCode.INTERNAL_ERROR, ex.getMessage(), null, null)
                            );
                            try {
                                results.put(row.requestId(), Json.mapper().writeValueAsString(output));
                            } catch (Exception ignored) {
                                results.put(row.requestId(), "{\"requestId\":\"" + row.requestId() + "\",\"status\":\"FAILED\"}");
                            }
                        }
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        this.lastSuccessCount = successCount[0];
        this.lastFailCount = failCount[0];
        return results;
    }

    private BatchOutputLine processSingleRow(BatchInputLine row) {
        try {
            PromptClient.PromptResult result = promptClient.evaluate(row.model(), row.prompt(), row.metadata());
            return BatchOutputLine.success(row.requestId(), result.response(), result.latencyMs());
        } catch (PromptClient.PromptException ex) {
            return BatchOutputLine.failed(row.requestId(), ex.error());
        }
    }
}
