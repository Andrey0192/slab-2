package org.example;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public class ClientHandler implements Runnable {
    AtomicLong last = new AtomicLong(0);

    private static final int EXPIRE_MS = 10_000;
    private final ScheduledExecutorService sched;
    AtomicBoolean printed = new AtomicBoolean(false);
    long startNs = System.nanoTime();


    private static final int BUFFER_LENGTH = 1024;
    private static final int OK = 0x01;
    private static final int NO = 0x00;

    private final String clientId;
    private static final int HEART_BEAT_MS = 3_000;

    public Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;
    private boolean running = true;

    AtomicLong total = new AtomicLong(0);






public ClientHandler(Socket socket, ScheduledExecutorService sched) throws IOException {
        this.socket = socket;
        this.clientId = socket.getRemoteSocketAddress().toString();
        this.sched = sched;
        try {
            socket.setSoTimeout(EXPIRE_MS);
            in =new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        while (socket.isConnected() && running) {
            receiveFile();
        }
    }

    @Override
    public void run() {

    }

    public void receiveFile() throws IOException {

        int typeFile = in.readInt();
        int nameLen = in.readInt();
        long fileSize = in.readLong();
        byte[] nameBytes = new byte[nameLen];
        in.readFully(nameBytes, 0, nameLen);
        String fileName = new String(nameBytes);

        Path path = Paths.get("uploads");
        Path full = path.resolve(fileName).normalize();
        byte[] buffer = new byte[BUFFER_LENGTH];
        boolean ok = false;
        SpeedPrinter sp = new SpeedPrinter(clientId, fileName, total, last, startNs, fileSize, printed);

        ScheduledFuture<?> tick = sched.scheduleAtFixedRate(sp, 3, 3, TimeUnit.SECONDS);
        try (OutputStream fos = Files.newOutputStream(full, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long rest = fileSize;
            while (rest > 0) {
                int want = (int) Math.min(BUFFER_LENGTH, rest);
                int read = in.read(buffer, 0, want);
                if (read == -1) {
                    System.out.println("Read EOF");
                    break;
                }
                fos.write(buffer, 0, read);
                total.addAndGet(read);
                rest -= want;
            }
            fos.flush();
            ok = (total.get() == fileSize);

            if (ok) {
                out.writeInt(OK);
            } else {
                out.writeByte(NO);
            }
            out.flush();
            if (!ok) throw new IOException("Size mismatch: got " + total.get() + " expected " + fileSize);

        } catch (Throwable e) {
            System.err.println("[" + clientId + "] error: " + e.getMessage());
        }
    }
}

class SpeedPrinter implements Runnable {
    private final String clientId;
    private final String fileName;
    private final AtomicLong total;
    private final AtomicLong last;
    private final long startNs;
    private final long expect;
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
        long dTime = TimeUnit.SECONDS.toNanos(3);
        if (tAll < dTime) dTime = (tAll == 0 ? 1 : tAll);

        double instBps = dBytes * 1e9 / (double) dTime;
        double avgBps  = cur    * 1e9 / (double) Math.max(1, tAll);

        System.out.printf(
                "[%s] %s | t=%.2fs | inst=%.0f B/s | avg=%.0f B/s | %d/%d bytes%n",
                clientId, fileName, tAll / 1e9, instBps, avgBps, cur, expect
        );
        printed.set(true);
    }
}