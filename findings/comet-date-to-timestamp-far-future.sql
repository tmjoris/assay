-- tz: America/New_York
-- expect-differs: comet
CREATE NAMESPACE IF NOT EXISTS assay.repro;
CREATE TABLE assay.repro.t (d DATE) USING iceberg;
INSERT INTO assay.repro.t VALUES (DATE '2038-07-01'), (DATE '2100-07-01'), (DATE '9999-07-01');
SELECT d, CAST(d AS TIMESTAMP) FROM assay.repro.t ORDER BY d;
