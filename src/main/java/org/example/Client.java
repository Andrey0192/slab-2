package org.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Client {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SO_TIMEOUT_MS = 60_000;

    private Client() {}


    public static int sendFile(Path file, String host, int port) {
        try {
            boolean ok = doSend(file, host, port);
            if (ok) {
                System.out.println("OK: transfer successful");
                return 0;
            } else {
                System.out.println("FAIL: server reported an error");
                return 1;
            }
        } catch (ProtocolException e) {
            System.err.println("Bad input/protocol: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("Network/I/O error: " + e.getMessage());
            return 2;
        }
    }

    private static boolean doSend(Path file, String host, int port) throws IOException, ProtocolException {
        Path path = file.toAbsolutePath().normalize();

        if (!Files.isRegularFile(path)) {
            throw new ProtocolException("Not a regular file: " + path);
        }

        long size = Files.size(path);
        if (size < 0 || size > Protocol.MAX_FILE_SIZE) {
            throw new ProtocolException("Bad file size: " + size);
        }

        byte[] nameBytes = path.getFileName().toString().getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length == 0 || nameBytes.length > Protocol.MAX_NAME_BYTES) {
            throw new ProtocolException("File name must be 1.." + Protocol.MAX_NAME_BYTES + " bytes in UTF-8");
        }

        byte[] buf = new byte[Protocol.IO_BUFFER_SIZE];

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(SO_TIMEOUT_MS);
            s.setTcpNoDelay(true);

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), Protocol.IO_BUFFER_SIZE));
                 DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8));
                 InputStream fin = new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ), Protocol.IO_BUFFER_SIZE)) {

                out.writeInt(nameBytes.length);
                out.writeLong(size);
                out.write(nameBytes);

                long remain = size;
                while (remain > 0) {
                    int want = (int) Math.min(buf.length, remain);
                    int r = fin.read(buf, 0, want);
                    if (r == -1) throw new EOFException("Unexpected EOF while reading local file");
                    out.write(buf, 0, r);
                    remain -= r;
                }
                out.flush();

                int status = in.read();
                return status == Protocol.OK;
            }
        }
    }
}
