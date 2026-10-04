package dev.oreslang.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JNI boundary for Oreslang networking.
 *
 * This class deliberately does not use java.net.Socket, ServerSocket,
 * SocketChannel, or java.net.http. All transport I/O crosses directly into the
 * platform socket API through liboresnet.
 *
 * Package-private by design: Oreslang Java interop must not be able to import
 * the raw-fd JNI surface. Guest-facing networking goes through OresNet and
 * opaque NativeSocketHandle instances.
 */
final class NativeSocketBridge {
    private static final String LIBRARY = "oresnet";

    static {
        loadNativeLibrary();
    }

    private NativeSocketBridge() { }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.net.native.path");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        Path local = Path.of("target", "native", System.mapLibraryName(LIBRARY))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }

        System.loadLibrary(LIBRARY);
    }

    private static native long connect(String host, int port, int timeoutMillis) throws IOException;
    private static native long listen(String host, int port, int backlog, boolean reuseAddress) throws IOException;
    private static native long accept(long fd) throws IOException;

    private static native int read(long fd, byte[] bytes, int offset, int length) throws IOException;
    private static native int write(long fd, byte[] bytes, int offset, int length) throws IOException;
    private static native int available(long fd) throws IOException;

    private static native void shutdownInput(long fd) throws IOException;
    private static native void shutdownOutput(long fd) throws IOException;
    private static native void close(long fd) throws IOException;

    private static native String remoteAddress(long fd) throws IOException;
    private static native String localAddress(long fd) throws IOException;
    private static native int remotePort(long fd) throws IOException;
    private static native int localPort(long fd) throws IOException;
    static native String[] resolveAll(String host) throws IOException;

    private static native void setTcpNoDelay(long fd, boolean enabled) throws IOException;
    private static native boolean getTcpNoDelay(long fd) throws IOException;
    private static native void setKeepAlive(long fd, boolean enabled) throws IOException;
    private static native boolean getKeepAlive(long fd) throws IOException;
    private static native void setReuseAddress(long fd, boolean enabled) throws IOException;
    private static native boolean getReuseAddress(long fd) throws IOException;
    private static native void setReceiveBufferSize(long fd, int bytes) throws IOException;
    private static native int getReceiveBufferSize(long fd) throws IOException;
    private static native void setSendBufferSize(long fd, int bytes) throws IOException;
    private static native int getSendBufferSize(long fd) throws IOException;
    private static native void setSoTimeout(long fd, int timeoutMillis) throws IOException;
    private static native int getSoTimeout(long fd) throws IOException;
    private static native void setSendTimeout(long fd, int timeoutMillis) throws IOException;
    private static native int getSendTimeout(long fd) throws IOException;


    private static NativeSocketHandle wrapFd(long fd) throws IOException {
        try {
            return new NativeSocketHandle(fd);
        } catch (RuntimeException | Error failure) {
            try {
                close(fd);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static NativeSocketHandle connectHandle(String host, int port, int timeoutMillis) throws IOException {
        return wrapFd(connect(host, port, timeoutMillis));
    }

    static NativeSocketHandle listenHandle(
            String host,
            int port,
            int backlog,
            boolean reuseAddress) throws IOException {
        return wrapFd(listen(host, port, backlog, reuseAddress));
    }

    static NativeSocketHandle accept(NativeSocketHandle listener) throws IOException {
        return listener.withFd(fd -> wrapFd(accept(fd)));
    }

    static int read(
            NativeSocketHandle handle,
            byte[] bytes,
            int offset,
            int length) throws IOException {
        return handle.withFd(fd -> read(fd, bytes, offset, length));
    }

    static int write(
            NativeSocketHandle handle,
            byte[] bytes,
            int offset,
            int length) throws IOException {
        return handle.withFd(fd -> write(fd, bytes, offset, length));
    }

    static int available(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::available);
    }

    static void shutdownInput(NativeSocketHandle handle) throws IOException {
        handle.withFdVoid(NativeSocketBridge::shutdownInput);
    }

    static void shutdownOutput(NativeSocketHandle handle) throws IOException {
        handle.withFdVoid(NativeSocketBridge::shutdownOutput);
    }

    static void close(NativeSocketHandle handle) throws IOException {
        handle.closeWith(NativeSocketBridge::close);
    }

    static String remoteAddress(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::remoteAddress);
    }

    static String localAddress(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::localAddress);
    }

    static int remotePort(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::remotePort);
    }

    static int localPort(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::localPort);
    }

    static void setTcpNoDelay(NativeSocketHandle handle, boolean enabled) throws IOException {
        handle.withFdVoid(fd -> setTcpNoDelay(fd, enabled));
    }

    static boolean getTcpNoDelay(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getTcpNoDelay);
    }

    static void setKeepAlive(NativeSocketHandle handle, boolean enabled) throws IOException {
        handle.withFdVoid(fd -> setKeepAlive(fd, enabled));
    }

    static boolean getKeepAlive(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getKeepAlive);
    }

    static void setReuseAddress(NativeSocketHandle handle, boolean enabled) throws IOException {
        handle.withFdVoid(fd -> setReuseAddress(fd, enabled));
    }

    static boolean getReuseAddress(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getReuseAddress);
    }

    static void setReceiveBufferSize(NativeSocketHandle handle, int bytes) throws IOException {
        handle.withFdVoid(fd -> setReceiveBufferSize(fd, bytes));
    }

    static int getReceiveBufferSize(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getReceiveBufferSize);
    }

    static void setSendBufferSize(NativeSocketHandle handle, int bytes) throws IOException {
        handle.withFdVoid(fd -> setSendBufferSize(fd, bytes));
    }

    static int getSendBufferSize(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getSendBufferSize);
    }

    static void setSoTimeout(NativeSocketHandle handle, int timeoutMillis) throws IOException {
        handle.withFdVoid(fd -> setSoTimeout(fd, timeoutMillis));
    }

    static int getSoTimeout(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getSoTimeout);
    }

    static int getSendTimeout(NativeSocketHandle handle) throws IOException {
        return handle.withFd(NativeSocketBridge::getSendTimeout);
    }

    static void setActorIoTimeout(
            NativeSocketHandle handle,
            int timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "actor I/O timeout must be positive and bounded");
        }
        handle.withFdVoid(fd -> {
            int previousSendTimeout = getSendTimeout(fd);
            setSendTimeout(fd, timeoutMillis);
            try {
                setSoTimeout(fd, timeoutMillis);
            } catch (IOException receiveTimeoutFailure) {
                try {
                    setSendTimeout(fd, previousSendTimeout);
                } catch (IOException rollbackFailure) {
                    receiveTimeoutFailure.addSuppressed(rollbackFailure);
                }
                throw receiveTimeoutFailure;
            }
        });
    }
}
