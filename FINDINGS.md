# Findings

Spark 4.0.2 is the reference. A difference counts as a bug in the native engine only after
checking what Spark's source or documentation says the answer should be. Each `.sql` file in
[findings/](findings) is a complete reproduction that was replayed on a clean warehouse.

Versions tested: Spark 4.0.2, Iceberg 1.11.0, Comet 1.2.0, Gluten 1.7.0 with Velox revision
`5b248e83`.

## Comet: a filter on `nullif` drops rows when the second argument is NULL

Reproduction: [comet-nullif-filter.sql](findings/comet-nullif-filter.sql)

`nullif(TRUE, NULL)` is `TRUE`, because `TRUE = NULL` is not true. All three engines agree when the
expression is selected as a column, and Comet disagrees once it is a filter:

| Query | Spark and Velox | Comet |
|---|---|---|
| `SELECT d, nullif(TRUE, b)`, row with NULL `b` | `2.5, true` | `2.5, true` |
| `WHERE nullif(TRUE, b)` | rows `2.5` and `9.5` | row `9.5` only |
| `stddev_pop(d)` over that filter | `3.5` | `0.0` |

The query succeeds and returns a wrong answer, which makes this the most serious kind of finding
here. A search of Comet issues found no report of it. A search can miss one.

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

## Velox: `upper` and `initcap` turn `ß` into `ẞ`

Reproduction: [velox-upper-sharp-s.sql](findings/velox-upper-sharp-s.sql)

| Engine | `upper('ß')` | `initcap('ß')` | `upper('éa ßb')` |
|---|---|---|---|
| Spark | `SS` | `Ss` | `ÉA SSB` |
| Comet | `SS` | `Ss` | `ÉA SSB` |
| Velox | `ẞ` | `ẞ` | `ÉA ẞB` |

Spark's result comes from Java's full case mapping, which expands `ß` to two letters. Velox maps
it to the single capital letter `ẞ` (U+1E9E). On German text, anything grouped, joined or filtered
on `upper(name)` gets a different answer. A search of Velox issues found no report of it.

## Velox: `second` and `date_trunc` with sub-minute zone offsets

Reproduction: [velox-second-historic-zone.sql](findings/velox-second-historic-zone.sql), run with
`--tz America/New_York`.

New York's offset before 1883 was local mean time, 4 hours 56 minutes 2 seconds behind UTC. For
`TIMESTAMP '1582-10-15 00:00:00'`:

| Engine | `second(ts)` | `date_trunc('MINUTE', ts)` in UTC |
|---|---|---|
| Spark | `0` | `04:56:02` |
| Comet | `0` | `04:56:02` |
| Velox | `2` | `04:56:00` |

The wall clock reads 00:00:00, so `0` is correct. Velox appears to work from the UTC value at this
precision, and modern dates are unaffected, because today's offsets are whole minutes. It appeared
in generated queries as a wrong `INTERSECT` result and a wrong window partition. Its impact is low
outside historical data.

## Velox: `CAST(date AS STRING)` after year 9999

Reproduction: [velox-date-string-year-10000.sql](findings/velox-date-string-year-10000.sql)

Spark prints `+10000-09-30` with a leading plus sign, which Comet matches. Velox prints
`10000-09-30`. It has a low impact, since such dates are almost always placeholders.

## Velox and Comet: `cbrt` differs from Spark in the last bits

Reproduction: [cbrt-last-bit.sql](findings/cbrt-last-bit.sql)

| Input | Spark | Comet | Velox |
|---|---|---|---|
| `27.0` | `3.0` | `3.0` | `3.0000000000000004` |
| `2.5` | `1.3572088082974532` | `1.3572088082974534` | `1.3572088082974532` |

These differences pass the 1e-9 double tolerance, and Assay caught them because `hash(cbrt(d))`
turns a one-bit difference into a different integer. Comet tracks this class of difference in
[comet#6797](https://github.com/apache/datafusion-comet/issues/6797). It matters only when an exact
value is compared or hashed.

## Comet: `lpad` fails on a NULL padding value

Reproduction: [comet-lpad-null-pad.sql](findings/comet-lpad-null-pad.sql)

The join leaves the padding column NULL, which reaches Comet as a NULL constant:

```
Unsupported arguments [Array(StringArray ...), Array(PrimitiveArray<Int32> ...), Scalar(Utf8(NULL))]
for function rpad/lpad/read_side_padding.
```

Spark returns NULL from `lpad`, so the query succeeds, and Velox matches it. Comet has open work
on neighbouring cases of this function, [comet#7450](https://github.com/apache/datafusion-comet/pull/7450)
and [comet#7452](https://github.com/apache/datafusion-comet/issues/7452). Whether they cover a NULL
pad is not established here.

## Velox: `power(1.0, Infinity)`

Reproduction: [velox-power-infinity.sql](findings/velox-power-infinity.sql)

Java defines `pow` as NaN when the absolute value of the base is 1 and the exponent is infinite,
and Spark follows it. C's `pow` returns 1, which is what Velox returns. A filter
`d <=> power(d, CAST('Infinity' AS DOUBLE))` keeps a row on Velox that Spark excludes.

## Velox: `round(x, NULL)` ignores the NULL

Reproduction: [velox-round-null-scale.sql](findings/velox-round-null-scale.sql)

`round(-0.0001, NULL)` is NULL on Spark and Comet, and `0.0000` on Velox. A NULL scale is unusual
in hand-written SQL, so its impact is low.

## Velox: `CAST(double AS DECIMAL)` on large values

Reproduction: [velox-cast-double-decimal.sql](findings/velox-cast-double-decimal.sql)

For the double nearest `-2^63`, Spark and Comet return `-9223372036854776000.0000000000` and Velox
returns `-9223372036854775808.0000000000`. Spark converts through the double's shortest decimal
text, while Velox keeps its exact binary value. Velox is numerically closer, but the answers
differ, and a comparison against the original column changes which rows pass.

## Spark: `LIKE` with supplementary characters

Reproduction: [spark-like-supplementary.sql](findings/spark-like-supplementary.sql)

This one is a Spark bug that Assay first reported as a Comet difference. For a non-literal pattern
such as `s LIKE p`:

| `s` | `p` | Spark 4.0.2 | Comet and Velox |
|---|---|---|---|
| `🦀` | `🦀` | false | true |
| `a🦀b` | `a🦀b` | false | true |
| `a🦀` | `a🦀%` | false | true |

`StringUtils.escapeLikeRegex` walks the pattern one UTF-16 unit at a time and quotes each unit
alone, which splits an emoji's surrogate pair. A fix was proposed upstream as SPARK-55453.

## Not confirmed

These appeared in runs but are not counted as bugs.

Velox lost rows in two queries on GitHub Actions. `WHERE a0.d > CAST(0.0 AS DOUBLE)` returned no
rows where Spark returned 2, on data with one NaN and one `5.25`. An `IN` subquery with a `BETWEEN`
on a NaN-holding column returned nothing where Spark returned 11, because adaptive execution saw
an empty build side. Neither reproduced on a laptop, including with a NaN as the first row of the
file, so the cause is unknown.

Spark in interpreted mode raised `NUMERIC_VALUE_OUT_OF_RANGE` on seed 7, New York, on a query that
compiled Spark answered.

Comet returned a timestamp one hour different from Spark for `CAST(date AS TIMESTAMP)` after year
9999 in New York. It has not been replayed or checked against the time zone rules.
