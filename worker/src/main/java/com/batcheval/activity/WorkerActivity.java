package com.batcheval.activity;

import com.batcheval.accessor.S3Accessor;
import com.batcheval.accessor.SqsAccessor;
import com.batcheval.business.BatchProcessBusiness;
import com.batcheval.business.PromptClient;
import com.batcheval.business.S3EventParser;
import com.batcheval.config.AppConfig;
import com.batcheval.dao.JobDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.UUID;

/** Activity layer — consumes S3 → SQS notifications and runs batch jobs. */
public final class WorkerActivity {

    private static final Logger log = LoggerFactory.getLogger(WorkerActivity.class);
    private static volatile boolean running = true;

    private WorkerActivity() {}

    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            log.info("shutdown requested");
        }));

        AppConfig config = AppConfig.load();
        try (
                JobDao jobDao = new JobDao(config);
                S3Accessor s3Accessor = new S3Accessor(config);
                SqsAccessor sqsAccessor = new SqsAccessor(config)
        ) {
            s3Accessor.ensureBucket();
            sqsAccessor.ensureQueue();
            s3Accessor.configureInputNotifications(sqsAccessor.queueArn());
            PromptClient promptClient = new PromptClient(config);
            BatchProcessBusiness business = new BatchProcessBusiness(config, jobDao, s3Accessor, promptClient);

            log.info("worker started (concurrency={}, queue={}, prompt={})",
                    config.workerMaxConcurrency(),
                    config.sqsQueueUrl(),
                    config.useOpenAiPromptApi() ? "gradient-ai" : "mock");

            while (running) {
                for (Message message : sqsAccessor.receive()) {
                    if (!running) {
                        break;
                    }
                    try {
                        UUID fileId = S3EventParser.parseInputFileId(message.body());
                        var job = jobDao.getJobByFileId(fileId)
                                .orElseThrow(() -> new IllegalStateException("no job for file_id " + fileId));
                        business.processJob(job.jobId());
                        sqsAccessor.delete(message.receiptHandle());
                    } catch (Exception ex) {
                        log.error("failed processing message {}", message.messageId(), ex);
                    }
                }
            }
        }
    }
}
