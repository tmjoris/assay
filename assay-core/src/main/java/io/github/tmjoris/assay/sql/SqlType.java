package io.github.tmjoris.assay.sql;

/** The column types Assay generates and compares. */
public enum SqlType {
    BOOLEAN("BOOLEAN"),
    INT("INT"),
    BIGINT("BIGINT"),
    DOUBLE("DOUBLE"),
    DEC18("DECIMAL(18,4)"),
    DEC38("DECIMAL(38,10)"),
    STRING("STRING"),
    DATE("DATE"),
    TIMESTAMP("TIMESTAMP");

    private final String sql;

    SqlType(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public boolean isNumeric() {
        return this == INT || this == BIGINT || this == DOUBLE || this == DEC18 || this == DEC38;
    }
}
