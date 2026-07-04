package com.batcheval.business;

import com.batcheval.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses S3 → SQS event notification payloads. */
public final class S3EventParser {

    private static final Pattern INPUT_KEY =
            Pattern.compile("^inputs/(?:priority|standard)/([0-9a-f-]{36})\\.jsonl$");

    private S3EventParser() {}

    /** @return file_id embedded in an {@code inputs/{file_id}.jsonl} key */
    public static UUID parseInputFileId(String messageBody) throws Exception {
        JsonNode root = Json.mapper().readTree(messageBody);
        JsonNode records = root.get("Records");
        if (records == null || !records.isArray() || records.isEmpty()) {
            throw new IllegalArgumentException("not an S3 event notification");
        }
        String key = records.get(0).path("s3").path("object").path("key").asText(null);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("S3 event missing object key");
        }
        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        Matcher matcher = INPUT_KEY.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unexpected S3 key: " + key);
        }
        return UUID.fromString(matcher.group(1));
    }
}
