-- The same two rows give a different answer on every engine, depending only on how they were written.
CREATE NAMESPACE IF NOT EXISTS assay.repro;
CREATE TABLE assay.repro.one_file (rid BIGINT, d DOUBLE) USING iceberg;
INSERT INTO assay.repro.one_file SELECT /*+ COALESCE(1) */ * FROM VALUES (119L, CAST('NaN' AS DOUBLE)), (121L, CAST(5.25 AS DOUBLE)) AS v(rid, d);
CREATE TABLE assay.repro.two_files (rid BIGINT, d DOUBLE) USING iceberg;
INSERT INTO assay.repro.two_files VALUES (119, CAST('NaN' AS DOUBLE));
INSERT INTO assay.repro.two_files VALUES (121, CAST(5.25 AS DOUBLE));
SELECT 'one file', rid FROM assay.repro.one_file WHERE d > CAST(0.0 AS DOUBLE) ORDER BY rid;
SELECT 'two files', rid FROM assay.repro.two_files WHERE d > CAST(0.0 AS DOUBLE) ORDER BY rid;
