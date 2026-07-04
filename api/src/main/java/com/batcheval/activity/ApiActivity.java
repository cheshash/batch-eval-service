package com.batcheval.activity;

import com.batcheval.accessor.S3Accessor;
import com.batcheval.accessor.SqsAccessor;
import com.batcheval.business.BatchIngestBusiness;
import com.batcheval.business.BusinessValidationException;
import com.batcheval.config.AppConfig;
import com.batcheval.dao.JobDao;
import com.batcheval.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Activity layer — HTTP entry points. Delegates to {@link BatchIngestBusiness}. */
public final class ApiActivity {

    private static final Logger log = LoggerFactory.getLogger(ApiActivity.class);

    private ApiActivity() {}

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        try (
                JobDao jobDao = new JobDao(config);
                S3Accessor s3Accessor = new S3Accessor(config);
                SqsAccessor sqsAccessor = new SqsAccessor(config)
        ) {
            s3Accessor.ensureBucket();
            sqsAccessor.ensureQueue();
            s3Accessor.configureInputNotifications(sqsAccessor.queueArn());
            BatchIngestBusiness business = new BatchIngestBusiness(config, jobDao, s3Accessor);
            Javalin app = createApp(config, business);
            app.start(config.apiPort());
            Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
            log.info("API listening on port {}", config.apiPort());
            Thread.currentThread().join();
        }
    }

    static Javalin createApp(AppConfig config, BatchIngestBusiness business) {
        return Javalin.create(cfg -> {
                    cfg.showJavalinBanner = false;
                    cfg.jsonMapper(new JavalinJackson(Json.mapper(), false));
                })
                .get("/health", ctx -> ctx.json(Map.of("status", "ok")))
                .post(config.apiPrefix() + "/batches", ctx -> submitBatch(ctx, business))
                .get(config.apiPrefix() + "/batches/{jobId}", ctx -> getBatchStatus(ctx, business))
                .get(config.apiPrefix() + "/batches/{jobId}/results", ctx -> getBatchResults(ctx, business))
                .exception(BusinessValidationException.class, ApiActivity::handleValidation)
                .exception(Exception.class, (ex, ctx) -> {
                    log.error("unhandled error", ex);
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                    ctx.json(Map.of("message", "internal server error"));
                });
    }

    private static void handleValidation(BusinessValidationException ex, Context ctx) {
        if (!ex.details().isEmpty()) {
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT);
            ctx.json(Map.of("message", ex.getMessage(), "details", ex.details()));
        } else if (ex.getMessage().contains("not found")) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("message", ex.getMessage()));
        } else if (ex.getMessage().contains("consumed")) {
            ctx.status(HttpStatus.CONFLICT);
            ctx.json(Map.of("message", ex.getMessage()));
        } else {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("message", ex.getMessage()));
        }
    }

    private static void submitBatch(Context ctx, BatchIngestBusiness business) throws Exception {
        UploadedFile uploaded = ctx.uploadedFile("file");
        if (uploaded == null) {
            throw new BusinessValidationException("multipart field 'file' is required");
        }
        String fileName = uploaded.filename();
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessValidationException("file name is required");
        }
        var job = business.submitBatch(fileName, requireNonNull(uploaded.content()).readAllBytes());
        ctx.status(HttpStatus.CREATED);
        ctx.json(business.toJobStatus(job));
    }

    private static void getBatchStatus(Context ctx, BatchIngestBusiness business) throws Exception {
        UUID jobId = UUID.fromString(ctx.pathParam("jobId"));
        ctx.json(business.getJobStatus(jobId));
    }

    private static void getBatchResults(Context ctx, BatchIngestBusiness business) throws Exception {
        UUID jobId = UUID.fromString(ctx.pathParam("jobId"));
        ctx.json(business.getResultsDownload(jobId));
    }
}
