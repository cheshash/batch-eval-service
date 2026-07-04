package com.batcheval.business;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class S3EventParserTest {

    @Test
    void parsesFileIdFromS3EventBody() throws Exception {
        UUID fileId = UUID.randomUUID();
        String body = """
                {
                  "Records": [{
                    "eventName": "ObjectCreated:Put",
                    "s3": {
                      "object": { "key": "inputs/priority/%s.jsonl" }
                    }
                  }]
                }
                """.formatted(fileId);

        assertThat(S3EventParser.parseInputFileId(body)).isEqualTo(fileId);
    }
}
