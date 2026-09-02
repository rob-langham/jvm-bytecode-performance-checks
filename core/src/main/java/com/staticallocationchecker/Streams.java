package com.staticallocationchecker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The pieces of {@code InputStream} that only arrived after Java 8.
 *
 * <p>The library targets release 8 so that the checker and the agent can run on the oldest JVM
 * whose bytecode they claim to analyse. {@code InputStream.readAllBytes} is Java 9, and it is read
 * often enough here - every class file the checker opens goes through it - to be worth one shared
 * implementation rather than a drain loop repeated at each call site.
 */
final class Streams {

    private static final int BUFFER_SIZE = 8192;

    private Streams() {
    }

    /** Reads the stream to its end. The stream is left open; the caller owns it. */
    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            collected.write(buffer, 0, read);
        }
        return collected.toByteArray();
    }
}
