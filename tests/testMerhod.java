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

@Aspect
public class MethodUsageAspect {

    private static final int MAX_LINES_PER_METHOD = 200;

    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RingBuffer> CALLS = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(MethodUsageAspect::printSummary, "method-usage-summary"));
    }

    // CATCH-ALL for your compiled classes. Skips getters/setters and this aspect.
    @Before("execution(* *(..))"
          + " && !execution(* *.get*(..))"
          + " && !execution(* *.set*(..))"
          + " && !within(org.csa.facets.infra.MethodUsageAspect)")
    public void onEnter(JoinPoint jp) {
        String methodKey = jp.getSignature().getDeclaringTypeName() + "#" + jp.getSignature().getName();

        COUNTS.computeIfAbsent(methodKey, k -> new LongAdder()).increment();

        String ts = TS_FMT.format(Instant.now());
        String args = Arrays.toString(jp.getArgs());
        CALLS.computeIfAbsent(methodKey, k -> new RingBuffer(MAX_LINES_PER_METHOD))
             .add("[" + ts + "] " + methodKey + "(" + args + ")");
    }

    private static void printSummary() {
        System.out.println("===== Method Usage Summary =====");
        List<Map.Entry<String, LongAdder>> sorted = COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .toList();
        for (Map.Entry<String, LongAdder> e : sorted) {
            String method = e.getKey();
            long count = e.getValue().sum();
            System.out.printf("%8d  %s%n", count, method);
            RingBuffer rb = CALLS.get(method);
            if (rb != null) rb.forEach(line -> System.out.println("          " + line));
        }
        System.out.println("================================");
    }

    private static final class RingBuffer {
        private final String[] buf;
        private int i = 0, n = 0;
        RingBuffer(int capacity) { this.buf = new String[Math.max(1, capacity)]; }
        synchronized void add(String s) { buf[i] = s; i = (i + 1) % buf.length; if (n < buf.length) n++; }
        synchronized void forEach(java.util.function.Consumer<String> c) {
            int start = (i - n + buf.length) % buf.length;
            for (int k = 0; k < n; k++) c.accept(buf[(start + k) % buf.length]);
        }
    }
}