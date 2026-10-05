package dev.oreslang.fs;

import dev.oreslang.runtime.NativeLibraryLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JNI-only filesystem primitive boundary.
 *
 * <p>No java.nio.file Files API is used for guest file I/O here. Java only
 * marshals UTF-8 paths/byte arrays and delegates to liboresfs.</p>
 */
final class NativeFileBridge {
    private static final String LIBRARY = "oresfs";
    private static final int MAX_PATH_BYTES = 64 * 1024;

    static {
        NativeLibraryLoader.load(LIBRARY, "ores.fs.native.path");
    }

    private NativeFileBridge() { }

    private static native long openRead(byte[] pathUtf8) throws IOException;
    private static native long openWriteTruncate(byte[] pathUtf8) throws IOException;
    private static native long openWriteAppend(byte[] pathUtf8) throws IOException;
    private static native int read(long fd, byte[] bytes, int offset, int length) throws IOException;
    private static native int write(long fd, byte[] bytes, int offset, int length) throws IOException;
    private static native long size(long fd) throws IOException;
    private static native void fsync(long fd) throws IOException;
    private static native void close(long fd) throws IOException;
    private static native void removeFile(byte[] pathUtf8) throws IOException;

    private static byte[] pathBytes(String path) {
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("filesystem path cannot contain NUL");
        }
        byte[] encoded = path.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0) {
            throw new IllegalArgumentException("filesystem path cannot be empty");
        }
        if (encoded.length > MAX_PATH_BYTES) {
            throw new IllegalArgumentException("filesystem path is too long");
        }
        return encoded;
    }

    private static NativeFileHandle wrapFd(long fd) throws IOException {
        try {
            return new NativeFileHandle(fd);
        } catch (RuntimeException | Error failure) {
            try {
                close(fd);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static NativeFileHandle openReadHandle(String path) throws IOException {
        return wrapFd(openRead(pathBytes(path)));
    }

    static NativeFileHandle openWriteTruncateHandle(String path) throws IOException {
        return wrapFd(openWriteTruncate(pathBytes(path)));
    }

    static NativeFileHandle openWriteAppendHandle(String path) throws IOException {
        return wrapFd(openWriteAppend(pathBytes(path)));
    }

    static int read(
            NativeFileHandle handle,
            byte[] bytes,
            int offset,
            int length) throws IOException {
        return handle.withFd(fd -> read(fd, bytes, offset, length));
    }

    static int write(
            NativeFileHandle handle,
            byte[] bytes,
            int offset,
            int length) throws IOException {
        return handle.withFd(fd -> write(fd, bytes, offset, length));
    }

    static long size(NativeFileHandle handle) throws IOException {
        return handle.withFd(NativeFileBridge::size);
    }

    static void fsync(NativeFileHandle handle) throws IOException {
        handle.withFdVoid(NativeFileBridge::fsync);
    }

    static void close(NativeFileHandle handle) throws IOException {
        handle.closeWith(NativeFileBridge::close);
    }

    static void removeFile(String path) throws IOException {
        removeFile(pathBytes(path));
    }
}
