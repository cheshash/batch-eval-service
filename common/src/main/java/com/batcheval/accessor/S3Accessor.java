package com.batcheval.accessor;

import com.batcheval.config.AppConfig;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.FilterRule;
import software.amazon.awssdk.services.s3.model.FilterRuleName;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.NotificationConfigurationFilter;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.S3KeyFilter;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/** Accessor — S3 object storage for batch input and result JSONL files. */
public class S3Accessor implements AutoCloseable {

    private final AppConfig config;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3Accessor(AppConfig config) {
        this.config = config;
        var builder = S3Client.builder()
                .region(Region.of(config.awsRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (config.awsEndpointUrl() != null) {
            builder.endpointOverride(config.awsEndpointUrl()).forcePathStyle(true);
        }
        this.s3 = builder.build();

        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(config.awsRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (config.awsEndpointUrl() != null) {
            presignerBuilder.endpointOverride(config.awsEndpointUrl());
        }
        this.presigner = presignerBuilder.build();
    }

    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(config.s3Bucket()).build());
        } catch (NoSuchBucketException ex) {
            s3.createBucket(CreateBucketRequest.builder().bucket(config.s3Bucket()).build());
        }
    }

    /** Wire S3 object-created events on {@code inputs/} prefix to the job queue. */
    public void configureInputNotifications(String queueArn) {
        var notification = NotificationConfiguration.builder()
                .queueConfigurations(QueueConfiguration.builder()
                        .queueArn(queueArn)
                        .events(Event.S3_OBJECT_CREATED)
                        .filter(NotificationConfigurationFilter.builder()
                                .key(S3KeyFilter.builder()
                                        .filterRules(FilterRule.builder()
                                                .name(FilterRuleName.PREFIX)
                                                .value("inputs/")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
        s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
                .bucket(config.s3Bucket())
                .notificationConfiguration(notification)
                .build());
    }

    public String presignedGet(String key) {
        var getRequest = GetObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .build();
        var presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(config.downloadUrlTtl())
                .getObjectRequest(getRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }

    public void uploadBytes(byte[] content, String key) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(config.s3Bucket())
                        .key(key)
                        .contentType("application/x-ndjson")
                        .build(),
                RequestBody.fromBytes(content)
        );
    }

    public void uploadFile(Path localPath, String key) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(config.s3Bucket())
                        .key(key)
                        .contentType("application/x-ndjson")
                        .build(),
                RequestBody.fromFile(localPath)
        );
    }

    public void uploadJsonl(String key, Iterable<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(config.s3Bucket())
                        .key(key)
                        .contentType("application/x-ndjson")
                        .build(),
                RequestBody.fromBytes(builder.toString().getBytes(StandardCharsets.UTF_8))
        );
    }

    public boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(config.s3Bucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    public void forEachLine(String key, Consumer<String> consumer) {
        var response = s3.getObject(GetObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .build());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    consumer.accept(line);
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("failed reading S3 object", ex);
        }
    }

    public void deleteObject(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .build());
    }

    /** {@code inputs/priority/} when the job contains priority-model rows; else {@code inputs/standard/}. */
    public String inputKey(UUID fileId, boolean highPriority) {
        String tier = highPriority ? "priority" : "standard";
        return "inputs/" + tier + "/" + fileId + ".jsonl";
    }

    public String resultKey(UUID jobId) {
        return "results/" + jobId + ".jsonl";
    }

    @Override
    public void close() {
        s3.close();
        presigner.close();
    }
}
