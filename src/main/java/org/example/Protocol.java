package org.example;

import java.nio.charset.StandardCharsets;

public final class Protocol {
    private Protocol() {}

    public static final byte OK = 0x01;
    public static final byte FAIL = 0x00;

    public static final int MAX_NAME_BYTES = 4096;
    public static final long MAX_FILE_SIZE = 1_000_000_000_000L; // 1 TB

    public static final int IO_BUFFER_SIZE = 256 * 1024;

    public static final int SPEED_PERIOD_SECONDS = 3;

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
