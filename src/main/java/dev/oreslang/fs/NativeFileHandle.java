package dev.oreslang.fs;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Opaque JVM-side identity for a native file descriptor.
 *
 * <p>The raw descriptor never becomes an Oreslang value. Generation identity
 * plus the liveness gate prevents a stale guest capability from targeting a
 * later descriptor after the kernel reuses the numeric fd.</p>
 */
final class NativeFileHandle {
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
            throw new IllegalStateException("native file handle generation space exhausted");
        }
        return value;
    }

    private final long generation = nextGeneration();
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private long fd;

    NativeFileHandle(long fd) {
        if (fd < 0) throw new IllegalArgumentException("native file fd must be non-negative");
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
            if (fd < 0) throw new IOException("native file handle is closed");
            return operation.run(fd);
        } finally {
            gate.readLock().unlock();
        }
    }

    void withFdVoid(FdIoVoid operation) throws IOException {
        gate.readLock().lock();
        try {
            if (fd < 0) throw new IOException("native file handle is closed");
            operation.run(fd);
        } finally {
            gate.readLock().unlock();
        }
    }

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
        return "NativeFileHandle[generation=" + generation + ",open=" + isOpen() + "]";
    }
}
