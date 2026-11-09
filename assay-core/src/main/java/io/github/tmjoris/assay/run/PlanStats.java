package io.github.tmjoris.assay.run;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts how much of a final physical plan really ran natively.
 *
 * <p>Without this a native engine that quietly fell back to the JVM would look like a perfect
 * match, when all that was compared is Spark with itself. It must be given the plan as it stood
 * after execution: Gluten rewrites plans inside adaptive execution, so a plan printed earlier shows
 * only vanilla Spark operators.
 */
public record PlanStats(int nativeOperators, int jvmOperators) {

    private static final Pattern OPERATOR = Pattern.compile("^[\\s:+\\-|]*(?:[*^]\\(\\d+\\)\\s*)?([A-Za-z]+)");

    /** Plumbing around a plan's real work, which runs regardless of where the operators do. */
    private static final Set<String> STRUCTURAL =
            Set.of(
                    "AdaptiveSparkPlan",
                    "ResultQueryStage",
                    "ShuffleQueryStage",
                    "BroadcastQueryStage",
                    "TableCacheQueryStage",
                    "InputAdapter",
                    "InputIteratorTransformer",
                    "WholeStageCodegen",
                    "CollectLimit",
                    "TakeOrderedAndProject",
                    "ReusedExchange",
                    "CometColumnarToRow",
                    "CometSparkColumnarToColumnar",
                    "ColumnarToRow",
                    "RowToColumnar",
                    "VeloxColumnarToRow",
                    "RowToVeloxColumnar",
                    "VeloxResizeBatches",
                    "ColumnarCollectLimit");

    public static PlanStats parse(String plan) {
        int nativeOps = 0;
        int jvmOps = 0;
        for (String line : plan.split("\n")) {
            Matcher m = OPERATOR.matcher(line);
            if (!m.find() || line.isBlank()) {
                continue;
            }
            String name = m.group(1);
            if (STRUCTURAL.contains(name) || name.equals("Final") || name.equals("Initial")) {
                continue;
            }
            if (name.startsWith("Comet")
                    || name.endsWith("Transformer")
                    || name.startsWith("Velox")
                    || name.startsWith("Columnar")) {
                nativeOps++;
            } else {
                jvmOps++;
            }
        }
        return new PlanStats(nativeOps, jvmOps);
    }

    public boolean anyNative() {
        return nativeOperators > 0;
    }

    public boolean fullyNative() {
        return nativeOperators > 0 && jvmOperators == 0;
    }
}
