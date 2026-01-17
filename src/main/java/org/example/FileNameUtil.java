package org.example;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class FileNameUtil {
    private FileNameUtil() {}

    public static String sanitize(String rawName) throws ProtocolException {
        if (rawName == null) throw new ProtocolException("filename is null");

        Path fn = Paths.get(rawName).getFileName();
        if (fn == null) throw new ProtocolException("bad filename");

        String name = fn.toString().strip();

        name = name.replaceAll("[\\x00-\\x1F\\x7F]", "_");

        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");

        name = name.strip();

        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new ProtocolException("bad filename");
        }

        return name;
    }
}
