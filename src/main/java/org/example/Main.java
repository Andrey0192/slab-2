package org.example;

import java.net.InetAddress;
import java.nio.file.Path;


public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usageAndExit();
            return;
        }

        String mode = args[0].toLowerCase();

        if ("server".equals(mode)) {
            runServer(args);
            return;
        }

        if ("client".equals(mode)) {
            runClient(args);
            return;
        }

        usageAndExit();
    }

    private static void runServer(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            usageAndExit();
            return;
        }

        int port = parsePort(args[1]);
        InetAddress bind = (args.length == 3) ? InetAddress.getByName(args[2]) : null;

        try (Server server = Server.createDefault(port, bind)) {
            server.serve();
        }
    }

    private static void runClient(String[] args) {
        if (args.length != 4) {
            usageAndExit();
            return;
        }

        Path file = Path.of(args[1]);
        String host = args[2];
        int port = parsePort(args[3]);

        int exit = Client.sendFile(file, host, port);
        System.exit(exit);
    }

    private static int parsePort(String s) {
        try {
            int port = Integer.parseInt(s);
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return port;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad port: " + s);
        }
    }

    private static void usageAndExit() {
        System.err.println(
                "Usage:\n" +
                        "  server <port> [bindAddress]\n" +
                        "  client <filePath> <host> <port>\n\n" +
                        "Examples:\n" +
                        "  server 5000\n" +
                        "  client /path/to/file.bin localhost 5000\n"
        );
        System.exit(2);
    }
}
