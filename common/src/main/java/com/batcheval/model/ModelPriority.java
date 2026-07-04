package com.batcheval.model;

import java.util.Comparator;
import java.util.List;

/** Model tier rules for worker scheduling. */
public final class ModelPriority {

    private ModelPriority() {}

    public static boolean isPriorityModel(String model, String priorityModel) {
        return model != null && model.equalsIgnoreCase(priorityModel);
    }

    public static boolean jobHasPriorityRows(List<BatchInputLine> rows, String priorityModel) {
        return rows.stream().anyMatch(row -> isPriorityModel(row.model(), priorityModel));
    }

    public static Comparator<BatchInputLine> rowComparator(String priorityModel) {
        return (a, b) -> Boolean.compare(
                isPriorityModel(b.model(), priorityModel),
                isPriorityModel(a.model(), priorityModel)
        );
    }
}
