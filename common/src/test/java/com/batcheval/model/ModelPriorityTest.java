package com.batcheval.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriorityTest {

    private static final String PRIORITY = "meta-llama-3-8b-instruct";

    @Test
    void detectsPriorityModelCaseInsensitively() {
        assertThat(ModelPriority.isPriorityModel("Meta-Llama-3-8b-Instruct", PRIORITY)).isTrue();
        assertThat(ModelPriority.isPriorityModel("other-model", PRIORITY)).isFalse();
    }

    @Test
    void sortsPriorityRowsFirst() {
        BatchInputLine priority = new BatchInputLine("p1", PRIORITY, "a", Map.of());
        BatchInputLine standard = new BatchInputLine("s1", "other-model", "b", Map.of());

        List<BatchInputLine> rows = List.of(standard, priority);
        rows = rows.stream().sorted(ModelPriority.rowComparator(PRIORITY)).toList();

        assertThat(rows.get(0).requestId()).isEqualTo("p1");
        assertThat(rows.get(1).requestId()).isEqualTo("s1");
    }
}
