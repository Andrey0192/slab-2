package org.example;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public final class SpeedReporter implements Runnable {
    private final String clientId;
    private final String fileName;
    private final long expectedBytes;
    private final AtomicLong totalBytes;
    private final AtomicBoolean printedAtLeastOnce;

    private final long startNs = System.nanoTime();
    private final AtomicLong lastBytes = new AtomicLong(0);
    private final AtomicLong lastNs = new AtomicLong(startNs);

    public SpeedReporter(
            String clientId,
            String fileName,
            long expectedBytes,
            AtomicLong totalBytes,
            AtomicBoolean printedAtLeastOnce
    ) {
        this.clientId = clientId;
        this.fileName = fileName;
        this.expectedBytes = expectedBytes;
        this.totalBytes = totalBytes;
        this.printedAtLeastOnce = printedAtLeastOnce;
    }

    @Override
    public void run() {
        printNow();
    }

    public void printNow() {
        long now = System.nanoTime();
        long curBytes = totalBytes.get();

        long prevBytes = lastBytes.getAndSet(curBytes);
        long prevNs = lastNs.getAndSet(now);

        long dtNs = Math.max(1L, now - prevNs);
        double inst = (curBytes - prevBytes) * 1e9 / (double) dtNs;

        long totalDtNs = Math.max(1L, now - startNs);
        double avg = curBytes * 1e9 / (double) totalDtNs;

        double pct = expectedBytes == 0 ? 100.0 : (curBytes * 100.0 / (double) expectedBytes);
        long tSec = TimeUnit.NANOSECONDS.toSeconds(totalDtNs);

        System.out.printf(
                "[%s] %s | t=%ds | inst=%.2f B/s | avg=%.2f B/s | %d/%d (%.1f%%)%n",
                clientId, fileName, tSec, inst, avg, curBytes, expectedBytes, pct
        );

        printedAtLeastOnce.set(true);
    }
}
