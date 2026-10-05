package dev.oreslang.fs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeFileBridgeTest {

    @Test
    void nativeRoundTripUsesOpaqueHandleAndPosixFileIo() throws Exception {
        Path path = Files.createTempFile("oreslang-native-fs-", ".txt");
        try {
            NativeFileHandle output = NativeFileBridge.openWriteTruncateHandle(path.toString());
            byte[] payload = "native-file-ok".getBytes(StandardCharsets.UTF_8);
            int offset = 0;
            while (offset < payload.length) {
                int at = offset;
                int count = NativeFileBridge.write(
                        output, payload, at, payload.length - at);
                assertTrue(count > 0);
                offset += count;
            }
            NativeFileBridge.fsync(output);
            assertEquals(payload.length, NativeFileBridge.size(output));
            NativeFileBridge.close(output);
            assertFalse(output.isOpen());

            NativeFileHandle input = NativeFileBridge.openReadHandle(path.toString());
            byte[] received = new byte[payload.length];
            int read = NativeFileBridge.read(input, received, 0, received.length);
            assertEquals(payload.length, read);
            assertArrayEquals(payload, received);
            assertEquals(-1, NativeFileBridge.read(input, received, 0, received.length));
            NativeFileBridge.close(input);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void staleHandleCannotTargetAReusedNumericFd() throws Exception {
        NativeFileHandle stale = new NativeFileHandle(17);
        stale.closeWith(fd -> { });
        assertFalse(stale.isOpen());
        assertThrows(java.io.IOException.class, () -> stale.withFd(fd -> fd));

        NativeFileHandle replacement = new NativeFileHandle(17);
        assertNotEquals(stale.generation(), replacement.generation());
        assertTrue(replacement.isOpen());
    }
}
