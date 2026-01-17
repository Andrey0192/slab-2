package org.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientHandler {
    private static final int MAX_COLLISION_ATTEMPTS = 1000;

    private final Socket socket;
    private final Path uploadsRoot;
    private final ScheduledExecutorService scheduler;

    public ClientHandler(Socket socket, Path uploadsRoot, ScheduledExecutorService scheduler) {
        this.socket = socket;
        this.uploadsRoot = uploadsRoot;
        this.scheduler = scheduler;
    }

    public void start() {
        run();
    }

    private void run() {
        final String clientId = String.valueOf(socket.getRemoteSocketAddress());
        final byte[] buf = new byte[Protocol.IO_BUFFER_SIZE];

        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), Protocol.IO_BUFFER_SIZE));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024))) {

            try {
                boolean ok = handleOneClient(clientId, in, buf);
                out.writeByte(ok ? Protocol.OK : Protocol.FAIL);
                out.flush();
            } catch (ProtocolException e) {
                safeWriteStatus(out, Protocol.FAIL);
                System.err.println("[" + clientId + "] protocol error: " + e.getMessage());
            } catch (IOException e) {
                safeWriteStatus(out, Protocol.FAIL);
                System.err.println("[" + clientId + "] I/O error: " + e.getMessage());
            }

        } catch (IOException e) {
            System.err.println("[" + clientId + "] connection error: " + e.getMessage());
        }
    }

    private boolean handleOneClient(String clientId, DataInputStream in, byte[] buf)
            throws IOException, ProtocolException {

        int nameLen = in.readInt();
        if (nameLen <= 0 || nameLen > Protocol.MAX_NAME_BYTES) {
            throw new ProtocolException("bad nameLen: " + nameLen);
        }

        long fileSize = in.readLong();
        if (fileSize < 0 || fileSize > Protocol.MAX_FILE_SIZE) {
            throw new ProtocolException("bad fileSize: " + fileSize);
        }

        byte[] nameBytes = new byte[nameLen];
        in.readFully(nameBytes);

        String rawName = new String(nameBytes, StandardCharsets.UTF_8);
        String safeName = FileNameUtil.sanitize(rawName);

        OpenedFile of = openUniqueFile(safeName);

        AtomicLong total = new AtomicLong(0);
        AtomicBoolean printed = new AtomicBoolean(false);

        SpeedReporter reporter = new SpeedReporter(clientId, of.storedName, fileSize, total, printed);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                reporter,
                Protocol.SPEED_PERIOD_SECONDS,
                Protocol.SPEED_PERIOD_SECONDS,
                TimeUnit.SECONDS
        );

        boolean ok = false;
        try (OpenedFile opened = of;
             OutputStream fileOut = new BufferedOutputStream(opened.out, Protocol.IO_BUFFER_SIZE)) {

            long remain = fileSize;
            while (remain > 0) {
                int want = (int) Math.min(buf.length, remain);
                int r = in.read(buf, 0, want);
                if (r == -1) {
                    throw new EOFException("unexpected end of stream");
                }
                fileOut.write(buf, 0, r);
                total.addAndGet(r);
                remain -= r;
            }
            fileOut.flush();

            ok = (total.get() == fileSize);
            return ok;

        } finally {
            future.cancel(false);

            if (!printed.get()) {
                reporter.printNow();
            }

            if (!ok) {
                try {
                    Files.deleteIfExists(of.path);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void safeWriteStatus(DataOutputStream out, byte status) {
        try {
            out.writeByte(status);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private OpenedFile openUniqueFile(String safeName) throws IOException, ProtocolException {
        String base = safeName;
        String ext = "";

        int dot = safeName.lastIndexOf('.');
        if (dot > 0 && dot < safeName.length() - 1) {
            base = safeName.substring(0, dot);
            ext = safeName.substring(dot);
        }

        for (int i = 0; i < MAX_COLLISION_ATTEMPTS; i++) {
            String candidateName = (i == 0) ? safeName : (base + "_" + i + ext);

            Path candidate = uploadsRoot.resolve(candidateName).normalize();
            if (!candidate.startsWith(uploadsRoot)) {
                throw new ProtocolException("bad filename (path traversal)");
            }

            try {
                OutputStream os = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new OpenedFile(candidate, candidateName, os);
            } catch (java.nio.file.FileAlreadyExistsException e) {
            }
        }

        throw new IOException("too many name collisions in uploads directory");
    }

    private static final class OpenedFile implements AutoCloseable {
        final Path path;
        final String storedName;
        final OutputStream out;

        OpenedFile(Path path, String storedName, OutputStream out) {
            this.path = path;
            this.storedName = storedName;
            this.out = out;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
