package com.batcheval.business;

import com.batcheval.accessor.S3Accessor;
import com.batcheval.config.AppConfig;
import com.batcheval.dao.JobDao;
import com.batcheval.model.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchIngestBusinessSubmitTest {

    private JobDao jobDao;
    private BatchIngestBusiness business;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = new AppConfig(
                0, "/v1", "jdbc:sqlite::memory:", "us-east-1", null,
                "test-bucket", "http://localhost/queue",
                Duration.ofMinutes(15), 90,
                URI.create("http://localhost:9000/v1/evaluate"),
                Duration.ofSeconds(60), 5, 1.0, 60.0, 4, 20, 300,
                1024 * 1024, 1000, "meta-llama-3-8b-instruct"
        );
        jobDao = new JobDao(config);
        S3Accessor s3 = new S3Accessor(config) {
            @Override
            public void uploadBytes(byte[] content, String key) {
                // no-op — S3 event → SQS is external
            }
        };
        business = new BatchIngestBusiness(config, jobDao, s3);
    }

    @AfterEach
    void tearDown() throws Exception {
        jobDao.close();
    }

    @Test
    void submitBatchRejectsNonJsonl() {
        assertThatThrownBy(() -> business.submitBatch("data.txt", new byte[]{1}))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void submitBatchPersistsSqlWithoutEnqueueingSqs() throws Exception {
        byte[] content = """
                {"request_id":"r1","model":"meta-llama-3-8b-instruct","prompt":"hello"}
                """.getBytes(StandardCharsets.UTF_8);

        var job = business.submitBatch("batch.jsonl", content);

        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.highPriority()).isTrue();
        assertThat(business.getJobStatus(job.jobId()).jobId()).isEqualTo(job.jobId());
        assertThat(jobDao.getJobByFileId(job.fileId())).isPresent();
    }

    @Test
    void submitBatchAcceptsExactlyMaxRowCount() throws Exception {
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            jsonl.append("{\"request_id\":\"r").append(i)
                    .append("\",\"model\":\"meta-llama-3-8b-instruct\",\"prompt\":\"hello\"}\n");
        }

        var job = business.submitBatch("batch.jsonl", jsonl.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void submitBatchRejectsOverMaxRowCount() {
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            jsonl.append("{\"request_id\":\"r").append(i)
                    .append("\",\"model\":\"meta-llama-3-8b-instruct\",\"prompt\":\"hello\"}\n");
        }

        assertThatThrownBy(() -> business.submitBatch(
                "batch.jsonl", jsonl.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("1000");
    }
}
