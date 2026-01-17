package org.example;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Server implements AutoCloseable {
    private static final int WORKERS = 10;
    private static final int QUEUE_CAPACITY = 50;
    private static final int BACKLOG = 100;

    private static final int SO_TIMEOUT_MS = 30_000;

    private final int port;
    private final InetAddress bindAddress; // null => 0.0.0.0
    private final Path uploadsRoot;

    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile ServerSocket serverSocket;

    public static Server createDefault(int port, InetAddress bindAddress) throws IOException {
        return new Server(port, bindAddress, Paths.get("uploads"));
    }

    public Server(int port, InetAddress bindAddress, Path uploadsDir) throws IOException {
        this.port = port;
        this.bindAddress = bindAddress;

        this.uploadsRoot = uploadsDir.toAbsolutePath().normalize();
        Files.createDirectories(this.uploadsRoot);

        AtomicInteger n = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "client-handler-" + n.getAndIncrement());
            t.setDaemon(false);
            return t;
        };

        this.pool = new ThreadPoolExecutor(
                WORKERS, WORKERS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                tf,
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "speed-reporter");
            t.setDaemon(true);
            return t;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (Exception ignored) {
            }
        }, "shutdown-hook"));
    }

    public void serve() throws IOException {
        ServerSocket ss = new ServerSocket();
        this.serverSocket = ss;

        SocketAddress bind = (bindAddress == null)
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);

        ss.setReuseAddress(true);
        ss.bind(bind, BACKLOG);

        System.out.println("Listening on " + ss.getLocalSocketAddress() + " -> " + uploadsRoot);

        while (running.get()) {
            try {
                Socket s = ss.accept();
                configure(s);

                var c = new ClientHandler(s, uploadsRoot, scheduler);
                try {
                    pool.execute(c::start);
                } catch (RejectedExecutionException rex) {
                    safeClose(s);
                    System.err.println("Rejected client " + s.getRemoteSocketAddress() + " (server overloaded)");
                }

            } catch (SocketException se) {
                if (!running.get()) break;
                System.err.println("Server socket error: " + se.getMessage());
            } catch (IOException ioe) {
                if (!running.get()) break;
                System.err.println("Accept I/O error: " + ioe.getMessage());
            }
        }
    }

    private static void configure(Socket s) throws SocketException {
        s.setSoTimeout(SO_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
    }

    private static void safeClose(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws Exception {
        running.set(false);

        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }

        pool.shutdownNow();
        scheduler.shutdownNow();
    }
}
