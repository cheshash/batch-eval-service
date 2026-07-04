package com.batcheval.model;

import java.util.Map;

/** Maps batch input model names to provider model IDs (e.g. DigitalOcean Gradient). */
public final class ModelAliases {

    private static final Map<String, String> ALIASES = Map.of(
            "meta-llama-3-8b-instruct", "llama3-8b-instruct",
            "meta-llama-3-70b-instruct", "llama3.3-70b-instruct"
    );

    private ModelAliases() {}

    public static String resolve(String model) {
        if (model == null || model.isBlank()) {
            return model;
        }
        return ALIASES.getOrDefault(model.toLowerCase(), model);
    }
}
