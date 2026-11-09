# Findings

Spark 4.0.2 is the reference. A difference counts as a bug in the native engine only after
checking what Spark's source or documentation says the answer should be. Each `.sql` file in
[findings/](findings) is a complete reproduction that was replayed on a clean warehouse.

Versions tested: Spark 4.0.2, Iceberg 1.11.0, Comet 1.2.0, Gluten 1.7.0 with Velox revision
`5b248e83`.

## Velox: `lpad` and `rpad` fail on an empty padding string

Reproduction: [velox-lpad-empty-pad.sql](findings/velox-lpad-empty-pad.sql)

```sql
SELECT lpad(s, pmod(k, 20), s) FROM t   -- s is '' on the one remaining row
```

| Engine | Result |
|---|---|
| Spark | one row, an empty string |
| Comet | one row, an empty string |
| Velox | the query fails: `padString must not be empty` |

Spark's `UTF8String.lpad` handles this case on purpose: when `pad.numBytes() == 0` it returns
`substring(0, len)`. Velox's Spark `lpad` and `rpad` reach a shared helper in
`velox/functions/lib/string/StringImpl.h` that rejects an empty pad. That check was still on
Velox `main` when this was written. The result is a failed job caused by one row's value, since
any empty string in a pad column is enough.

A search of Velox and Gluten issues found no report of it. A search can miss one.

The check may be correct for Presto, which shares the helper, so a fix belongs in the Spark
function rather than the helper.

## Velox: `round` changes large doubles

Reproduction: [velox-round-large-double.sql](findings/velox-round-large-double.sql)

| Input | Spark and Comet `round(d, 0)` | Velox `round(d, 0)` |
|---|---|---|
| `1.0E16` | `1.0E16` | `1.0000000000000002E16` |
| `1.0E300` | `1.0E300` | `1.0000000000000002E300` |
| `1.7976931348623157E308` | `1.7976931348623157E308` | `Infinity` |

Assay found this through a join. The shrunk query was
`FULL JOIN ... ON a0.d = round(a0.d, 0) WHERE a1.b`, where Velox returned 1 row and Spark 2. In a
direct test, `WHERE d = round(d, 0)` returned 4 of 5 rows on Spark and 1 on Velox.

This is partly known. [velox#19422](https://github.com/facebookincubator/velox/pull/19422), open
when this was written, keeps results finite, which covers the `Infinity` case. It leaves full
parity with Spark's decimal rounding to a separate design discussion
([velox#19414](https://github.com/facebookincubator/velox/discussions/19414)).

## Velox: `CAST(double AS STRING)` prints the smallest double differently

Reproduction: [velox-cast-subnormal-double.sql](findings/velox-cast-subnormal-double.sql)

| Engine | `CAST(4.9E-324 AS STRING)` |
|---|---|
| Spark | `4.9E-324` |
| Comet | `4.9E-324` |
| Velox | `4.9406564584124654E-324` |

The numeric value is the same, so this only matters when the text is compared, joined on or
exported. A search found no report of it, and it has a low impact.
