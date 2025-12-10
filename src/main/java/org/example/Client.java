package org.example;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class Client {
    private static final int BUFFER_LENGTH =  256 * 1024;
    private static final int MAGIC = 0x12345678;
    private static final byte OK = 0x01, FAIL = 0x00;

    private static final int MAX_NAME_BYTES = 16;

    public static void main(String[] args) throws IOException {

        String host = "26.89.21.147";
        int port = 5000;
        Path uploads = Paths.get( "uploads").toAbsolutePath().normalize();
        Files.createDirectories(uploads);
        Path path = uploads.resolve(Paths.get("uploads", "123.mp4")).normalize();

        if (!Files.isRegularFile(path)) {
            System.err.println("Not a file: " + path);
            System.exit(3);
        }
        long size = Files.size(path);
        if (size < 0) {
            System.err.println("Bad size");
            System.exit(3);
        }
        byte[] nameBytes = path.getFileName().toString().getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length == 0 || nameBytes.length > MAX_NAME_BYTES) {
            System.err.println("File name must be 1.." + MAX_NAME_BYTES + " bytes in UTF-8");
            System.exit(3);
        }
        try (Socket s = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), BUFFER_LENGTH));
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8));
             InputStream fin = new BufferedInputStream(Files.newInputStream(path), BUFFER_LENGTH)) {


            out.writeInt(MAGIC);
            out.writeInt(nameBytes.length);
            out.writeLong(size);
            out.write(nameBytes);

            byte[] buf = new byte[BUFFER_LENGTH];
            long remain = size;
            while (remain > 0) {
                int r = fin.read(buf, 0, (int) Math.min(buf.length, remain));
                if (r == -1) break;
                out.write(buf, 0, r);
                remain -= r;
            }
            out.flush();

            int status = in.read();
            if (status == OK) {
                System.out.println("OK: the transfer is successful");
            } else {
                System.out.println("FAIL: the server reported an error");
                System.exit(1);
            }
        }
    }


}
