package org.csa.facets.infra;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Records every method call under org.csa.facets.., skipping getters/setters.
 * Stores timestamp + args for the last N calls per method and prints a summary at JVM shutdown.
 *
 * Works with AspectJ compile-time weaving via aspectj-maven-plugin.
 */
@Aspect
public class MethodUsageAspect {

    // How many recent calls to remember per method (to avoid unbounded memory)
    private static final int MAX_LINES_PER_METHOD = 200;

    // Per-method call counts
    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    // Per-method ring buffer of recent call lines (timestamp + args)
    private static final ConcurrentHashMap<String, RingBuffer> CALLS = new ConcurrentHashMap<>();

    // ISO-8601 timestamp in system zone
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    static {
        // Print once at JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(MethodUsageAspect::printSummary, "method-usage-summary"));
    }

    // Only your code, skip getters/setters, and avoid this aspect itself
    @Before("execution(* org.csa.facets..*(..))"
          + " && !execution(* org.csa.facets..get*(..))"
          + " && !execution(* org.csa.facets..set*(..))"
          + " && !within(org.csa.facets.infra.MethodUsageAspect)")
    public void onEnter(JoinPoint jp) {
        String methodKey = jp.getSignature().getDeclaringTypeName() + "#" + jp.getSignature().getName();

        COUNTS.computeIfAbsent(methodKey, k -> new LongAdder()).increment();

        String ts = TS_FMT.format(Instant.now());
        String args = Arrays.toString(jp.getArgs());
        String line = "[" + ts + "] " + methodKey + "(" + args + ")";

        CALLS.computeIfAbsent(methodKey, k -> new RingBuffer(MAX_LINES_PER_METHOD)).add(line);
    }

    private static void printSummary() {
        System.out.println("===== Method Usage Summary (org.csa.facets) =====");

        List<Map.Entry<String, LongAdder>> sorted = COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .toList();

        for (Map.Entry<String, LongAdder> e : sorted) {
            String method = e.getKey();
            long count = e.getValue().sum();
            System.out.printf("%8d  %s%n", count, method);

            RingBuffer rb = CALLS.get(method);
            if (rb != null) {
                rb.forEach(line -> System.out.println("          " + line));
            }
        }
        System.out.println("=================================================");
    }

    /** Simple synchronized ring buffer for recent lines. */
    private static final class RingBuffer {
        private final String[] buf;
        private int i = 0;
        private int n = 0;
        RingBuffer(int capacity) { this.buf = new String[Math.max(1, capacity)]; }
        synchronized void add(String s) {
            buf[i] = s;
            i = (i + 1) % buf.length;
            if (n < buf.length) n++;
        }
        synchronized void forEach(java.util.function.Consumer<String> c) {
            int start = (i - n + buf.length) % buf.length;
            for (int k = 0; k < n; k++) c.accept(buf[(start + k) % buf.length]);
        }
    }
}