-- expect-differs: velox
CREATE NAMESPACE IF NOT EXISTS assay.repro;
CREATE TABLE assay.repro.t (rid BIGINT, d DOUBLE) USING iceberg;
INSERT INTO assay.repro.t SELECT /*+ COALESCE(1) */ * FROM VALUES (119L, CAST('NaN' AS DOUBLE)), (121L, CAST(5.25 AS DOUBLE)) AS v(rid, d);
SELECT rid FROM assay.repro.t WHERE d > CAST(0.0 AS DOUBLE) ORDER BY rid;
SELECT rid FROM assay.repro.t WHERE d BETWEEN CAST(2.5 AS DOUBLE) AND CAST(9.0 AS DOUBLE) ORDER BY rid;
