package org.example;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class Server {
    private int port;
    private ScheduledExecutorService sched;
    InetAddress address ;
    

    Server () {
        try (ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(10, r -> {
            Thread thread = new Thread(r);
            thread.setName("Thread-" + thread.getId() + "-Server");
            thread.start();
            return thread;
        })) {
            try {
                address = InetAddress.getByName("localhost");
                try (ServerSocket serverSocket = new ServerSocket(port, 10, address)) {
                    Socket socket = serverSocket.accept();
                    ClientHandler clientHandler = new ClientHandler(socket, sched);
                    executor.execute(clientHandler);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdown));
        }
        this.sched = Executors.newScheduledThreadPool(5, r -> {
            Thread t = new Thread(r, "speed-reporter");
            t.setDaemon(true);
            return t;
        });

    }

    public static void main(String[] args) {
        int port = 12345;
        int backlog = 10;
        Server server = new Server();

    }
}
