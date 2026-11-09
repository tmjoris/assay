package io.github.tmjoris.assay.sql;

import java.util.List;

/**
 * One SQL function or operator: its rendering pattern and its types.
 *
 * <p>A pattern refers to its arguments as {@code {0}}, {@code {1}} and so on. Adding a function to
 * the fuzzer is one line in {@link Catalog}.
 */
public record Template(String pattern, SqlType result, List<SqlType> args, Kind kind) {

    public enum Kind {
        SCALAR,
        AGGREGATE,
        WINDOW
    }

    /**
     * Substitutes arguments in one pass, so braces inside a rendered string literal are never
     * mistaken for a placeholder.
     */
    public String render(List<String> rendered) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{' && i + 2 < pattern.length() && pattern.charAt(i + 2) == '}') {
                out.append(rendered.get(pattern.charAt(i + 1) - '0'));
                i += 3;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The name a finding's signature uses for this function, such as {@code substring}. */
    public String label() {
        return pattern.replaceAll("\\{\\d}", "_").replaceAll("\\s+", " ");
    }
}
