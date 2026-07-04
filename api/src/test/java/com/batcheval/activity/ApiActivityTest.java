package com.batcheval.activity;

import com.batcheval.accessor.S3Accessor;
import com.batcheval.business.BatchIngestBusiness;
import com.batcheval.config.AppConfig;
import com.batcheval.dao.JobDao;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApiActivityTest {

    private JobDao jobDao;
    private BatchIngestBusiness business;
    private Javalin app;

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
        S3Accessor s3Accessor = new S3Accessor(config) {
            @Override
            public void ensureBucket() {}

            @Override
            public void uploadBytes(byte[] content, String key) {}
        };
        business = new BatchIngestBusiness(config, jobDao, s3Accessor);
        app = ApiActivity.createApp(config, business);
    }

    @AfterEach
    void tearDown() throws Exception {
        app.stop();
        jobDao.close();
    }

    @Test
    void healthReturnsOk() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/health");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("ok");
        });
    }
}
