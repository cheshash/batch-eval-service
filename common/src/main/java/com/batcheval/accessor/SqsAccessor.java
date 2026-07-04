package com.batcheval.accessor;

import com.batcheval.config.AppConfig;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

/** Accessor — SQS queue fed by S3 object-created notifications. */
public class SqsAccessor implements AutoCloseable {

    private final AppConfig config;
    private final SqsClient sqs;
    private String queueUrl;

    public SqsAccessor(AppConfig config) {
        this.config = config;
        var builder = SqsClient.builder()
                .region(Region.of(config.awsRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (config.awsEndpointUrl() != null) {
            builder.endpointOverride(config.awsEndpointUrl());
        }
        this.sqs = builder.build();
        this.queueUrl = config.sqsQueueUrl();
    }

    public void ensureQueue() {
        if (config.awsEndpointUrl() == null) {
            return;
        }
        try {
            sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName("batch-eval-jobs").build());
        } catch (QueueDoesNotExistException ex) {
            var response = sqs.createQueue(CreateQueueRequest.builder().queueName("batch-eval-jobs").build());
            this.queueUrl = response.queueUrl();
        }
    }

    public String queueArn() {
        var response = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build());
        return response.attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    public List<Message> receive() {
        var response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(config.workerPollWaitSeconds())
                .visibilityTimeout(config.workerVisibilityTimeout())
                .build());
        return response.messages();
    }

    public void delete(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }

    @Override
    public void close() {
        sqs.close();
    }
}
