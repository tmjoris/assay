package io.github.tmjoris.assay.run;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The line protocol between the coordinator and a worker JVM, over the worker's stdin and stdout.
 *
 * <p>Comet and Gluten both patch Spark and cannot share a JVM, so each engine runs in its own
 * process. That also means a native crash kills only its own worker and becomes a finding. Fields
 * are tab-separated and free text is Base64, so SQL can never break the framing.
 *
 * <pre>
 * RUN  &lt;sql&gt; &lt;arrow path&gt;  -> OK &lt;rows&gt; &lt;millis&gt; &lt;final plan&gt;   or   ERR &lt;message&gt;
 * EXEC &lt;sql&gt;                -> OK 0 &lt;millis&gt; ""            or   ERR &lt;message&gt;
 * QUIT
 * </pre>
 */
public final class Protocol {

    public static final String READY = "READY";

    private Protocol() {}

    public static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }
}
