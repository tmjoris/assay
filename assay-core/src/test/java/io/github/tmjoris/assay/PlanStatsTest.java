package io.github.tmjoris.assay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tmjoris.assay.run.PlanStats;
import org.junit.jupiter.api.Test;

class PlanStatsTest {

    @Test
    void countsVeloxPlanAsNative() {
        String plan =
                """
                ResultQueryStage 0
                +- VeloxColumnarToRow
                   +- ^(2) RegularHashAggregateExecTransformer [k#1]
                      +- InputIteratorTransformer[k#1]
                         +- ShuffleQueryStage 0
                            +- ColumnarExchange SinglePartition
                               +- VeloxResizeBatches 1024
                                  +- ^(1) FilterExecTransformer (k#1 > 1)
                                     +- ^(1) IcebergScanTransformer w.d.fact
                """;
        PlanStats s = PlanStats.parse(plan);
        assertEquals(4, s.nativeOperators());
        assertEquals(0, s.jvmOperators());
        assertTrue(s.fullyNative());
    }

    @Test
    void countsCometPlanAsNative() {
        String plan =
                """
                CometColumnarToRow
                +- CometHashAggregate [k#1]
                   +- CometExchange SinglePartition
                      :- CometFilter (k#1 > 1)
                      +- CometIcebergNativeScan w.d.fact
                """;
        assertTrue(PlanStats.parse(plan).fullyNative());
    }

    @Test
    void vanillaPlanHasNoNativeOperators() {
        String plan =
                """
                *(2) HashAggregate(keys=[k#1])
                +- Exchange SinglePartition
                   +- *(1) ColumnarToRow
                      +- BatchScan w.d.fact
                """;
        PlanStats s = PlanStats.parse(plan);
        assertFalse(s.anyNative());
        assertEquals(3, s.jvmOperators());
    }

    @Test
    void mixedPlanIsNotFullyNative() {
        String plan =
                """
                Project [k#1]
                +- CometFilter (k#1 > 1)
                """;
        PlanStats s = PlanStats.parse(plan);
        assertTrue(s.anyNative());
        assertFalse(s.fullyNative());
    }
}
