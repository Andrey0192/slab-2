package org.example;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientHandler implements Runnable {
    private static final int BUF = 256 * 1024;
    private static final int MAX_NAME_BYTES = 4096;
    private static final long MAX_FILE_SIZE = 1_000_000_000_000L;
    private static final int MAGIC = 0x12345678;
    private static final byte OK = 0x01, FAIL = 0x00;

    private final Socket sock;
    private final ScheduledExecutorService sched;
    private final Path uploadsRoot;

    public ClientHandler(Socket sock, ScheduledExecutorService sched, Path uploadsRoot) {
        this.sock = sock;
        this.sched = sched;
        this.uploadsRoot = uploadsRoot;
    }

    @Override public void run() {
        String clientId = sock.getRemoteSocketAddress().toString();
        AtomicLong total = new AtomicLong(0);
        AtomicLong last  = new AtomicLong(0);
        AtomicBoolean printed = new AtomicBoolean(false);
        long startNs = System.nanoTime();

        try (Socket s = sock;
             DataInputStream in  = new DataInputStream(new BufferedInputStream(s.getInputStream(), BUF));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 8))) {

            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("bad magic");
            int nameLen = in.readInt();
            if (nameLen <= 0 || nameLen > MAX_NAME_BYTES) throw new IOException("bad nameLen");
            long fileSize = in.readLong();
            if (fileSize < 0 || fileSize > MAX_FILE_SIZE) throw new IOException("bad fileSize");

            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String rawName = new String(nameBytes, StandardCharsets.UTF_8);

            String name = Paths.get(rawName).getFileName().toString();
            Path target = uploadsRoot.resolve(name).normalize();


            SpeedPrinter sp = new SpeedPrinter(clientId, rawName, total, last, startNs, fileSize, printed);
            sched.scheduleAtFixedRate(sp, 3, 3, TimeUnit.SECONDS);

            boolean ok = false;
            try (OutputStream fout = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[BUF];
                long remain = fileSize;
                while (remain > 0) {
                    int want = (int) Math.min(buf.length, remain);
                    int r = in.read(buf, 0, want);
                    if (r == -1) break;
                    fout.write(buf, 0, r);
                    total.addAndGet(r);
                    remain -= r;
                }
                fout.flush();
                ok = (total.get() == fileSize);
            } catch (Throwable t) {
                try { Files.deleteIfExists(target); } catch (IOException ignore) {}
                throw t;
            } finally {
                if (!printed.get()) sp.printNow();
            }

            out.writeByte(ok ? OK : FAIL);
            out.flush();

            if (!ok) throw new IOException("size mismatch: got " + total.get() + " expected " + fileSize);

        } catch (Throwable e) {
            System.err.println("[" + clientId + "] " + e.getMessage());
        }
    }


    static final class SpeedPrinter implements Runnable {
        private final String clientId, fileName;
        private final AtomicLong total, last;
        private final long startNs, expect;
        private final AtomicBoolean printed;

        SpeedPrinter(String clientId, String fileName, AtomicLong total, AtomicLong last,
                     long startNs, long expect, AtomicBoolean printed) {
            this.clientId = clientId;
            this.fileName = fileName;
            this.total = total;
            this.last = last;
            this.startNs = startNs;
            this.expect = expect;
            this.printed = printed;
        }

        @Override public void run() { printNow(); }

        void printNow() {
            long now = System.nanoTime();
            long tAll = now - startNs;
            long cur = total.get();
            long prev = last.getAndSet(cur);
            long dBytes = cur - prev;
            long window = TimeUnit.SECONDS.toNanos(3);
            if (tAll < window) window = Math.max(1, tAll);

            double inst = dBytes * 1e9 / (double) window;
            double avg  = cur    * 1e9 / (double) Math.max(1, tAll);

            System.out.printf("[%s] %s | t=%.2fs | inst=%.0f B/s | avg=%.0f B/s | %d/%d%n",
                    clientId, fileName, tAll / 1e9, inst, avg, cur, expect);
            printed.set(true);
        }
    }
}
