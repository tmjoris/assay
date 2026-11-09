# Assay

Assay checks whether a native query engine gives the same answers as Apache Spark.

Companies switch on [Apache Comet](https://datafusion.apache.org/comet/) or
[Velox](https://velox-lib.io/) (through [Apache Gluten](https://gluten.apache.org/)) to cut
Spark's compute bill. Both replace Spark's JVM operators with native code and promise the same
results. When one of them rounds a number differently or mishandles a null, the query still
succeeds and a report is quietly wrong.

Assay generates random SQL over Apache Iceberg tables, runs each query on every engine, compares
the results exactly as Apache Arrow data, and shrinks each difference to a small reproduction.

## What it has found

These runs used Spark 4.0.2, Iceberg 1.11.0, Comet 1.2.0 and Gluten 1.7.0 with its bundled Velox.
Details and reproductions are in [FINDINGS.md](FINDINGS.md).

| Engine | Finding | Effect | Upstream status |
|---|---|---|---|
| Velox | `round(double, 0)` changes large values, and the largest double becomes `Infinity` | A filter on the rounded value dropped 4 of 5 rows | Known in part: [velox#19422](https://github.com/facebookincubator/velox/pull/19422) is open |
| Velox | `lpad` and `rpad` with an empty padding string | The whole query fails where Spark returns a value | No report found |
| Velox | `CAST(double AS STRING)` on the smallest double | Prints `4.9406564584124654E-324` where Spark prints `4.9E-324` | No report found |

Comet had no differences in these runs.

A 600-query run on a 4-core laptop (seed 2, 100 rows per table) took 27 minutes:

| Engine | Queries compared | At least one native operator | Fully native | Differences |
|---|---|---|---|---|
| Spark, interpreted (control) | 536 | n/a | n/a | 0 |
| Comet | 536 | 506 | 216 | 0 |
| Velox | 536 | 456 | 200 | 5 reports, 2 root causes |

Spark itself rejected the other 64 queries, which says nothing about any engine. The 5 reports
came from an earlier build that split one bug into several findings. The round bug appeared in
a separate 100-query run on seed 1.

A clean run shows only that these queries found no difference. Less than half of them ran fully
natively on either engine, and the rest mixed native and JVM operators.

## How it works

```
                 seed
                  |
        generate data and queries
                  |
   +--------------+---------------+
   |          |          |        |
 Spark     Spark       Comet    Velox      one JVM each, reading
 (reference) (interpreted,                 the same Iceberg tables
   |        control)     |        |
   +--------------+---------------+
                  |
     results written as Arrow files
                  |
      exact comparison, then recheck
                  |
   shrink the query, then the rows
                  |
   report.md and repro.sql for each finding
```

**One JVM per engine.** Comet and Gluten both patch Spark, so they cannot share a process. A
native crash then kills only its own worker, and it becomes a finding. The coordinator has no
Spark on its classpath and talks to workers over a small line protocol.

**Queries have one correct answer.** The generator leaves out anything two correct engines may
legitimately answer differently. That means no `rand()` or `first()`, window functions ordered
by a unique row id, and `LIMIT` only with a total ordering. Floating-point aggregates only see
bounded inputs, because a sum over values near 1e308 depends on the order rows arrive in.

**Data aims at known weak spots.** Columns mix a small domain, so joins find matches, with nulls,
NaN, infinities, negative zero, integer and decimal limits, awkward strings and extreme dates.
The four tables differ physically: a plain table, one split into several files, one partitioned
by a bucket transform, and one with merge-on-read delete files. Random join hints force the
broadcast, sort-merge and shuffle-hash paths.

**The comparison is strict where it can be.** Integers, decimals, strings and dates must match
exactly. Doubles get a relative tolerance of 1e-9, because parallel sums legitimately differ in
the last bits. Negative zero against positive zero is reported as its own, softer kind.

**Nothing is reported unless it recurs.** A difference has to repeat twice more before Assay
treats it as a finding. Shrinking removes query clauses and sub-expressions greedily, then uses
delta debugging on the rows, and the result is rechecked from a clean copy of the data.

**Two checks keep the results honest.** Spark in interpreted mode, with code generation and
adaptive execution off, runs as a control: a difference there points at the generator or at
Spark. Assay also reads each final physical plan and counts native operators, so a query that
quietly fell back to the JVM cannot pass as evidence for a native engine. The plan has to be read
after execution, since Gluten rewrites plans inside adaptive execution.

## Running it

You need Java 21. The build fetches everything else, and checks the Gluten download against its
release checksum.

```sh
./gradlew build

# Fuzz all engines. Output goes to runs/first, which must not exist yet.
./gradlew :assay-cli:run --args="--out runs/first --seed 1 --queries 200 --engines spark-interpreted,comet,velox"

# Replay a reproduction on each engine, side by side.
./gradlew :assay-cli:run --args="check --out runs/replay --file findings/velox-lpad-empty-pad.sql"
```

Each finding gets a directory under `runs/<name>/findings` holding `report.md`, `repro.sql` and
the Arrow results from both engines. `summary.json` has the counts for the run. The same seed
generates the same data and queries.

## Limits

- Spark is the reference, so a bug shared by Spark and the native engine is invisible. Each
  finding in [FINDINGS.md](FINDINGS.md) was checked against Spark's documented behaviour or source.
- ANSI mode is off, because Gluten 1.7 documents it as unsupported on Spark 4.0.
- Generated queries use scalar types only. Arrays, maps and structs are not covered.
- Tables hold a few hundred rows. That exercises semantics, but not spilling or memory pressure.
- Comet's default settings keep incompatible expressions on the JVM, so its clean result applies
  to that configuration.

## Layout

| Module | Contents |
|---|---|
| `assay-core` | SQL model, function catalog, data and query generators, comparison, shrinking |
| `assay-worker` | Runs on the engine's classpath, executes SQL, writes Arrow results |
| `assay-cli` | Coordinator: starts workers, drives the fuzz loop, writes reports |

Adding a function to the fuzzer is one line in `Catalog.java`.

## License

Apache 2.0
