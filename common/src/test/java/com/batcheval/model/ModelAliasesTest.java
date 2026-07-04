package com.batcheval.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAliasesTest {

    @Test
    void mapsKnownModelsToGradientIds() {
        assertThat(ModelAliases.resolve("meta-llama-3-8b-instruct")).isEqualTo("llama3-8b-instruct");
        assertThat(ModelAliases.resolve("llama3-8b-instruct")).isEqualTo("llama3-8b-instruct");
    }
}
