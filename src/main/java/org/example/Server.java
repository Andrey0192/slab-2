package org.example;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Server {
    private static final int WORKERS = 10;
    private static final int QUEUE   = 50;
    private static final int BACKLOG = 100;
    private static final int SO_TIMEOUT_MS = 30_000;

    private final int port;
    private final Path uploads;
    private final ExecutorService pool;
    private final ScheduledExecutorService sched;

    public Server(int port) throws IOException {
        this.port = port;
        this.uploads = Paths.get("uploads").toAbsolutePath().normalize();
        Files.createDirectories(uploads);

        ThreadFactory tf = r -> {
            Thread t = new Thread(r);
            t.setName("client-" + t.getName());
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(
                WORKERS, WORKERS,
                1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                tf,
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.sched = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("speed-reporter");
            return t;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pool.shutdownNow();
            sched.shutdownNow();
        }));
    }

    public void serve() throws IOException {
        try (ServerSocket ss = new ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.123"))) {
            ss.setReuseAddress(true);
            System.out.println("Listening on " + ss.getInetAddress() + ":" + port + " -> " + uploads);
            while (true) {
                Socket s = ss.accept();
                s.setSoTimeout(SO_TIMEOUT_MS);
                try {
                    pool.execute(new ClientHandler(s, sched, uploads));
                } catch (RejectedExecutionException rex) {
                    try { s.close(); } catch (IOException ignore) {}
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 5000;
        new Server(port).serve();
    }
}
