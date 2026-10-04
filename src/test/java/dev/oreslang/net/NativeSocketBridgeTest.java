package dev.oreslang.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class NativeSocketBridgeTest {

    @Test
    void resolvesLocalhostWithoutJavaNet() throws Exception {
        String[] addresses = NativeSocketBridge.resolveAll("localhost");
        assertNotNull(addresses);
        assertTrue(addresses.length >= 1);
        assertTrue(Arrays.stream(addresses).allMatch(value -> !value.isBlank()));
    }

    @Test
    void roundTripsTcpOverJniSocketSyscalls() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        assertTrue(port > 0);

        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Thread server = new Thread(() -> {
            NativeSocketHandle accepted = null;
            try {
                accepted = NativeSocketBridge.accept(listener);
                NativeSocketBridge.setActorIoTimeout(accepted, 3_000);

                byte[] request = new byte[4];
                int offset = 0;
                while (offset < request.length) {
                    int count = NativeSocketBridge.read(accepted, request, offset, request.length - offset);
                    if (count < 0) throw new AssertionError("unexpected EOF");
                    offset += count;
                }
                assertEquals("ping", new String(request, StandardCharsets.UTF_8));

                byte[] response = "pong".getBytes(StandardCharsets.UTF_8);
                int written = 0;
                while (written < response.length) {
                    written += NativeSocketBridge.write(
                            accepted, response, written, response.length - written);
                }
            } catch (Throwable error) {
                serverFailure.set(error);
            } finally {
                if (accepted != null && accepted.isOpen()) {
                    try { NativeSocketBridge.close(accepted); } catch (Exception ignored) { }
                }
            }
        }, "oresnet-test-server");
        server.start();

        NativeSocketHandle client = NativeSocketBridge.connectHandle("127.0.0.1", port, 3_000);
        try {
            NativeSocketBridge.setActorIoTimeout(client, 3_000);
            assertTrue(NativeSocketBridge.getSoTimeout(client) > 0);
            assertTrue(NativeSocketBridge.getSendTimeout(client) > 0);
            byte[] request = "ping".getBytes(StandardCharsets.UTF_8);
            assertEquals(4, NativeSocketBridge.write(client, request, 0, request.length));

            byte[] response = new byte[4];
            int offset = 0;
            while (offset < response.length) {
                int count = NativeSocketBridge.read(client, response, offset, response.length - offset);
                assertTrue(count > 0);
                offset += count;
            }
            assertEquals("pong", new String(response, StandardCharsets.UTF_8));
        } finally {
            NativeSocketBridge.close(client);
            NativeSocketBridge.close(listener);
        }

        server.join(5_000);
        assertFalse(server.isAlive(), "native TCP test server did not terminate");
        if (serverFailure.get() != null) {
            fail("server failed", serverFailure.get());
        }
    }
}
