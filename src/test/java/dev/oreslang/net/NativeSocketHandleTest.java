package dev.oreslang.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class NativeSocketHandleTest {

    @Test
    void generationsAreUniqueEvenWhenTheNumericFdIsReused() {
        NativeSocketHandle first = new NativeSocketHandle(7);
        NativeSocketHandle second = new NativeSocketHandle(7);

        assertNotEquals(first.generation(), second.generation());
        assertTrue(first.isOpen());
        assertTrue(second.isOpen());
    }

    @Test
    void closedHandleFailsClosedAndCannotTargetAReusedFd() throws Exception {
        NativeSocketHandle stale = new NativeSocketHandle(11);
        AtomicLong closedFd = new AtomicLong(-1);

        stale.closeWith(closedFd::set);

        assertEquals(11, closedFd.get());
        assertFalse(stale.isOpen());
        IOException error = assertThrows(
                IOException.class,
                () -> stale.withFd(fd -> fd));
        assertTrue(error.getMessage().contains("closed"));

        NativeSocketHandle replacement = new NativeSocketHandle(11);
        assertTrue(replacement.isOpen());
        assertNotEquals(stale.generation(), replacement.generation());
        long replacementFd = replacement.withFd(fd -> fd);
        assertEquals(11L, replacementFd);
    }

    @Test
    void closeWaitsForInflightIoBeforeInvalidatingDescriptor() throws Exception {
        NativeSocketHandle handle = new NativeSocketHandle(23);
        CountDownLatch ioEntered = new CountDownLatch(1);
        CountDownLatch allowIoExit = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicLong closedFd = new AtomicLong(-1);

        Thread io = new Thread(() -> {
            try {
                handle.withFd(fd -> {
                    assertEquals(23L, fd);
                    ioEntered.countDown();
                    try {
                        assertTrue(allowIoExit.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return null;
                });
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        Thread closer = new Thread(() -> {
            try {
                assertTrue(ioEntered.await(2, TimeUnit.SECONDS));
                handle.closeWith(closedFd::set);
                closeReturned.countDown();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });

        io.start();
        closer.start();

        assertTrue(ioEntered.await(2, TimeUnit.SECONDS));
        assertFalse(closeReturned.await(50, TimeUnit.MILLISECONDS),
                "close must wait for the operation holding the descriptor");
        allowIoExit.countDown();

        io.join(2_000);
        closer.join(2_000);
        assertFalse(handle.isOpen());
        assertEquals(23L, closedFd.get());
    }

    @Test
    void failedNativeCloseStillInvalidatesTheHandle() {
        NativeSocketHandle handle = new NativeSocketHandle(29);

        IOException failure = assertThrows(
                IOException.class,
                () -> handle.closeWith(fd -> {
                    throw new IOException("close failed");
                }));

        assertEquals("close failed", failure.getMessage());
        assertFalse(handle.isOpen());
        assertThrows(IOException.class, () -> handle.withFd(fd -> fd));
    }
}
