package dev.oreslang.net;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Opaque JVM-side socket identity.
 *
 * <p>The raw OS descriptor never leaves this package. Each handle has a unique
 * generation and a liveness gate so stale objects cannot accidentally target a
 * newly-created socket after the kernel recycles an fd number.</p>
 */
final class NativeSocketHandle {
    @FunctionalInterface
    interface FdIo<T> {
        T run(long fd) throws IOException;
    }

    @FunctionalInterface
    interface FdIoVoid {
        void run(long fd) throws IOException;
    }

    private static final AtomicLong NEXT_GENERATION = new AtomicLong(1L);

    private static long nextGeneration() {
        long value = NEXT_GENERATION.getAndIncrement();
        if (value <= 0) {
            throw new IllegalStateException(
                    "native socket handle generation space exhausted");
        }
        return value;
    }

    private final long generation = nextGeneration();
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private long fd;

    NativeSocketHandle(long fd) {
        if (fd < 0) throw new IllegalArgumentException("native socket fd must be non-negative");
        this.fd = fd;
    }

    long generation() {
        return generation;
    }

    boolean isOpen() {
        gate.readLock().lock();
        try {
            return fd >= 0;
        } finally {
            gate.readLock().unlock();
        }
    }

    <T> T withFd(FdIo<T> operation) throws IOException {
        gate.readLock().lock();
        try {
            if (fd < 0) throw new IOException("native socket handle is closed");
            return operation.run(fd);
        } finally {
            gate.readLock().unlock();
        }
    }

    void withFdVoid(FdIoVoid operation) throws IOException {
        gate.readLock().lock();
        try {
            if (fd < 0) throw new IOException("native socket handle is closed");
            operation.run(fd);
        } finally {
            gate.readLock().unlock();
        }
    }

    /**
     * Atomically invalidates the handle before closing the OS descriptor. The
     * write lock waits for in-flight I/O so close cannot race an operation that
     * already validated the old fd.
     */
    void closeWith(FdIoVoid closer) throws IOException {
        gate.writeLock().lock();
        try {
            if (fd < 0) return;
            long closingFd = fd;
            fd = -1;
            closer.run(closingFd);
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "NativeSocketHandle[generation=" + generation + ",open=" + isOpen() + "]";
    }
}
