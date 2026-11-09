-- Run on a Spark catalog named `assay`.
CREATE NAMESPACE IF NOT EXISTS assay.repro;
CREATE TABLE assay.repro.t (b BOOLEAN, d DOUBLE) USING iceberg;
INSERT INTO assay.repro.t VALUES (CAST(NULL AS BOOLEAN), CAST(2.5 AS DOUBLE)), (TRUE, CAST(7.5 AS DOUBLE)), (FALSE, CAST(9.5 AS DOUBLE));
SELECT d, nullif(TRUE, b) FROM assay.repro.t ORDER BY d;
SELECT d FROM assay.repro.t WHERE nullif(TRUE, b) ORDER BY d;
SELECT stddev_pop(d) FROM assay.repro.t WHERE nullif(TRUE, b);
