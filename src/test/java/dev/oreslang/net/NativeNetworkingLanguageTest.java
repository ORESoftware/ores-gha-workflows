package dev.oreslang.net;

import dev.oreslang.OresLanguage;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class NativeNetworkingLanguageTest {

    @Test
    void networkNamespaceRequiresExplicitCapability() {
        var program = TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val socket = net.Socket.new("127.0.0.1", 9);
                    socket.close();
                    return;
                  }
                end
                """));

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(
                        program,
                        IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)));
    }

    @Test
    void oresProgramUsesJavaShapedSocketApiOverNativeTransport() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                NativeSocketBridge.setSoTimeout(client, 3_000);
                byte[] incoming = new byte[4];
                readExactly(client, incoming);
                if (!"ping".equals(new String(incoming, StandardCharsets.UTF_8))) {
                    throw new AssertionError("unexpected request payload");
                }
                writeAll(client, "pong".getBytes(StandardCharsets.UTF_8));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (client != null && client.isOpen()) {
                    try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
                }
            }
        }, "oreslang-socket-language-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val socket = net.Socket.new("127.0.0.1", %d);
                    socket.setSoTimeout(3000);
                    val output = socket.getOutputStream();
                    output.writeString("ping");
                    val input = socket.getInputStream();
                    stdio.println(input.read());
                    stdio.println(input.read());
                    stdio.println(input.read());
                    stdio.println(input.read());
                    socket.close();
                    return;
                  }
                end
                """.formatted(port);

        String output = evaluate(program);
        assertTrue(output.contains("112")); // p
        assertTrue(output.contains("111")); // o
        assertTrue(output.contains("110")); // n
        assertTrue(output.contains("103")); // g

        NativeSocketBridge.close(listener);
        server.join(5_000);
        assertFalse(server.isAlive());
        if (failure.get() != null) fail("server failed", failure.get());
    }

    @Test
    void oresHttpClientParsesStatusHeadersAndBodyWithoutJavaHttpClient() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                NativeSocketBridge.setSoTimeout(client, 3_000);
                readHeaders(client);
                byte[] body = "native-http-ok".getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 201 Created\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "X-Ores-Net: native\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                writeAll(client, head.getBytes(StandardCharsets.ISO_8859_1));
                writeAll(client, body);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (client != null && client.isOpen()) {
                    try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
                }
            }
        }, "oreslang-http-language-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val request_builder = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/native");
                    request_builder.header("Accept", "text/plain");
                    val request = request_builder.GET().build();
                    val client = net.http.HttpClient.newHttpClient();
                    val handler = net.http.HttpResponse.BodyHandlers.ofString();
                    val response = client.send(request, handler);
                    stdio.println(response.statusCode());
                    stdio.println(response.body());
                    stdio.println(response.headers().firstValue("x-ores-net").orElse("missing"));
                    return;
                  }
                end
                """.formatted(port);

        String output = evaluate(program);
        assertTrue(output.contains("201"));
        assertTrue(output.contains("native-http-ok"));
        assertTrue(output.contains("native"));

        NativeSocketBridge.close(listener);
        server.join(5_000);
        assertFalse(server.isAlive());
        if (failure.get() != null) fail("server failed", failure.get());
    }


    @Test
    void oresServerSocketAcceptsAndWritesOverNativeTransport() throws Exception {
        NativeSocketHandle reservation = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(reservation);
        NativeSocketBridge.close(reservation);

        AtomicReference<String> guestOutput = new AtomicReference<>();
        AtomicReference<Throwable> guestFailure = new AtomicReference<>();

        String program = """
                define module app
                  pub fnc main() => void {
                    val bind_address = net.InetSocketAddress.new("127.0.0.1", %d);
                    val server = net.ServerSocket.new(%d, 16, bind_address);
                    val socket = server.accept();
                    socket.setSoTimeout(3000);
                    val input = socket.getInputStream();
                    stdio.println(input.read());
                    val output = socket.getOutputStream();
                    output.writeString("pong");
                    socket.close();
                    server.close();
                    return;
                  }
                end
                """.formatted(port, port);

        Thread guest = new Thread(() -> {
            try {
                guestOutput.set(evaluate(program));
            } catch (Throwable error) {
                guestFailure.set(error);
            }
        }, "oreslang-server-socket-language-test");
        guest.start();

        NativeSocketHandle client = connectEventually("127.0.0.1", port, 3_000);
        try {
            NativeSocketBridge.setSoTimeout(client, 3_000);
            writeAll(client, new byte[] {(byte) 'Q'});
            byte[] response = new byte[4];
            readExactly(client, response);
            assertEquals("pong", new String(response, StandardCharsets.UTF_8));
        } finally {
            NativeSocketBridge.close(client);
        }

        guest.join(5_000);
        assertFalse(guest.isAlive(), "Oreslang server socket test did not terminate");
        if (guestFailure.get() != null) fail("guest server failed", guestFailure.get());
        assertNotNull(guestOutput.get());
        assertTrue(guestOutput.get().contains("81")); // Q
    }

    @Test
    void oresHttpPostSendsBodyAndDecodesChunkedResponse() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                NativeSocketBridge.setSoTimeout(client, 3_000);
                String headers = readHeadersText(client);
                assertTrue(headers.startsWith("POST /submit HTTP/1.1"));
                int contentLength = headerInt(headers, "content-length");
                byte[] body = new byte[contentLength];
                readExactly(client, body);
                assertEquals("hello-native", new String(body, StandardCharsets.UTF_8));

                String response = "HTTP/1.1 200 OK\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + "6\r\nnative\r\n"
                        + "8\r\n-chunked\r\n"
                        + "0\r\n\r\n";
                writeAll(client, response.getBytes(StandardCharsets.ISO_8859_1));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (client != null && client.isOpen()) {
                    try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
                }
            }
        }, "oreslang-http-post-language-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val builder = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/submit");
                    builder.header("Content-Type", "text/plain");
                    val request = builder.POST(
                        net.http.HttpRequest.BodyPublishers.ofString("hello-native")
                    ).build();
                    val client = net.http.HttpClient.newHttpClient();
                    val response = client.send(request, net.http.HttpResponse.BodyHandlers.ofString());
                    stdio.println(response.statusCode());
                    stdio.println(response.body());
                    return;
                  }
                end
                """.formatted(port);

        String output = evaluate(program);
        assertTrue(output.contains("200"));
        assertTrue(output.contains("native-chunked"));

        NativeSocketBridge.close(listener);
        server.join(5_000);
        assertFalse(server.isAlive());
        if (failure.get() != null) fail("server failed", failure.get());
    }

    @Test
    void oresHttpClientFollowsRedirectsWithJavaShapedPolicy() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread server = new Thread(() -> {
            try {
                NativeSocketHandle first = NativeSocketBridge.accept(listener);
                try {
                    NativeSocketBridge.setSoTimeout(first, 3_000);
                    String firstHeaders = readHeadersText(first);
                    assertTrue(firstHeaders.startsWith("GET /start HTTP/1.1"));
                    String redirect = "HTTP/1.1 302 Found\r\n"
                            + "Location: /final\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n";
                    writeAll(first, redirect.getBytes(StandardCharsets.ISO_8859_1));
                } finally {
                    NativeSocketBridge.close(first);
                }

                NativeSocketHandle second = NativeSocketBridge.accept(listener);
                try {
                    NativeSocketBridge.setSoTimeout(second, 3_000);
                    String secondHeaders = readHeadersText(second);
                    assertTrue(secondHeaders.startsWith("GET /final HTTP/1.1"));
                    byte[] body = "redirect-ok".getBytes(StandardCharsets.UTF_8);
                    String head = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    writeAll(second, head.getBytes(StandardCharsets.ISO_8859_1));
                    writeAll(second, body);
                } finally {
                    NativeSocketBridge.close(second);
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "oreslang-http-redirect-language-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val client_builder = net.http.HttpClient.newBuilder();
                    val client = client_builder.followRedirects(net.http.HttpClient.Redirect.ALWAYS).build();
                    val request = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/start").GET().build();
                    val response = client.send(request, net.http.HttpResponse.BodyHandlers.ofString());
                    stdio.println(response.statusCode());
                    stdio.println(response.body());
                    stdio.println(response.previousResponse().isPresent());
                    return;
                  }
                end
                """.formatted(port);

        String output = evaluate(program);
        assertTrue(output.contains("200"));
        assertTrue(output.contains("redirect-ok"));
        assertTrue(output.contains("true"));

        NativeSocketBridge.close(listener);
        server.join(5_000);
        assertFalse(server.isAlive());
        if (failure.get() != null) fail("server failed", failure.get());
    }


    @Test
    void rejectsAmbiguousTransferEncodingAndContentLength() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                readHeadersText(client);
                String response = "HTTP/1.1 200 OK\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Content-Length: 5\r\n"
                        + "Connection: close\r\n\r\n"
                        + "5\r\nhello\r\n0\r\n\r\n";
                writeAll(client, response.getBytes(StandardCharsets.ISO_8859_1));
            } catch (Throwable ignored) {
            } finally {
                if (client != null && client.isOpen()) try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
            }
        }, "oreslang-http-ambiguous-framing-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val request = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/").GET().build();
                    net.http.HttpClient.newHttpClient().send(
                        request, net.http.HttpResponse.BodyHandlers.ofString());
                    return;
                  }
                end
                """.formatted(port);

        Exception error = assertThrows(Exception.class, () -> evaluate(program));
        assertTrue(error.toString().contains("ambiguous HTTP response framing"));
        NativeSocketBridge.close(listener);
        server.join(5_000);
    }

    @Test
    void requestTimeoutAppliesToResponseReads() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                readHeadersText(client);
                Thread.sleep(500);
            } catch (Throwable ignored) {
            } finally {
                if (client != null && client.isOpen()) try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
            }
        }, "oreslang-http-read-timeout-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val request = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/")
                        .timeout(100).GET().build();
                    net.http.HttpClient.newHttpClient().send(
                        request, net.http.HttpResponse.BodyHandlers.ofString());
                    return;
                  }
                end
                """.formatted(port);

        assertThrows(Exception.class, () -> evaluate(program));
        NativeSocketBridge.close(listener);
        server.join(5_000);
    }


    @Test
    void adversarialNetworkCapabilityCannotUseRawSocketSurfaces() throws Exception {
        IsolatePolicy contextPolicy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);
        IsolatePolicy adversarialActorPolicy = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);

        try (ActorRuntime runtime = new ActorRuntime(contextPolicy)) {
            var ref = runtime.<String>spawnPrivate(
                    adversarialActorPolicy,
                    factoryContext -> (message, actorContext) ->
                            NetworkAdmission.rejectAdversarial(
                                    IsolatePolicy.developer()
                                            .withCapabilities(IsolatePolicy.Capability.NETWORK),
                                    ActorRuntime.currentActorPolicy(),
                                    "test.raw-network"));

            ref.send("check");
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            Throwable failure = ref.failure().orElseThrow();
            assertInstanceOf(SecurityException.class, failure);
            assertTrue(failure.getMessage().contains("bounded stateless HTTP"), failure::getMessage);
        }

        String nativeBuiltin = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/oreslang/net/NativeNetBuiltin.java"));
        String publicNet = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/oreslang/net/OresNet.java"));
        assertTrue(nativeBuiltin.contains("NetworkAdmission.requireRawNetwork"));
        assertTrue(publicNet.contains("NetworkAdmission.requireRawNetwork"));
    }

    @Test
    void malformedChunkTrailerFailsClosed() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                NativeSocketBridge.setSoTimeout(client, 3_000);
                readHeadersText(client);
                String response = "HTTP/1.1 200 OK\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + "1\r\nx\r\n"
                        + "0\r\n"
                        + "broken-trailer\r\n\r\n";
                writeAll(client, response.getBytes(StandardCharsets.ISO_8859_1));
            } catch (Throwable ignored) {
            } finally {
                if (client != null && client.isOpen()) {
                    try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
                }
            }
        }, "oreslang-http-malformed-trailer-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val request = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/").GET().build();
                    net.http.HttpClient.newHttpClient().send(
                        request, net.http.HttpResponse.BodyHandlers.ofString());
                    return;
                  }
                end
                """.formatted(port);

        Exception error = assertThrows(Exception.class, () -> evaluate(program));
        assertTrue(error.toString().contains("malformed chunk trailer"), error::toString);
        NativeSocketBridge.close(listener);
        server.join(5_000);
    }

    @Test
    void patchRequestCarriesContentLength() throws Exception {
        NativeSocketHandle listener = NativeSocketBridge.listenHandle("127.0.0.1", 0, 16, true);
        int port = NativeSocketBridge.localPort(listener);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread server = new Thread(() -> {
            NativeSocketHandle client = null;
            try {
                client = NativeSocketBridge.accept(listener);
                NativeSocketBridge.setSoTimeout(client, 3_000);
                String headers = readHeadersText(client);
                assertTrue(headers.startsWith("PATCH /patch HTTP/1.1"));
                assertEquals(3, headerInt(headers, "content-length"));
                byte[] body = new byte[3];
                readExactly(client, body);
                assertEquals("abc", new String(body, StandardCharsets.UTF_8));
                writeAll(client, ("HTTP/1.1 204 No Content\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (client != null && client.isOpen()) {
                    try { NativeSocketBridge.close(client); } catch (Exception ignored) { }
                }
            }
        }, "oreslang-http-patch-content-length-test");
        server.start();

        String program = """
                define module app
                  pub fnc main() => void {
                    val builder = net.http.HttpRequest.newBuilder("http://127.0.0.1:%d/patch");
                    val request = builder.method(
                        "PATCH",
                        net.http.HttpRequest.BodyPublishers.ofString("abc")
                    ).build();
                    val response = net.http.HttpClient.newHttpClient().send(
                        request, net.http.HttpResponse.BodyHandlers.discarding());
                    stdio.println(response.statusCode());
                    return;
                  }
                end
                """.formatted(port);

        String output = evaluate(program);
        assertTrue(output.contains("204"));
        NativeSocketBridge.close(listener);
        server.join(5_000);
        if (failure.get() != null) fail("server failed", failure.get());
    }

    @Test
    void networkingImplementationKeepsProtocolEngineOutOfJava() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/oreslang/net/OresNet.java"));
        assertFalse(source.contains("java.net."));
        assertFalse(source.contains("java.net.http"));
        // Architectural end-state: HTTP wire parsing/serialization and URI
        // redirect resolution belong in Oreslang stdlib source, not in Java.
        assertFalse(source.contains("parseResponse(byte[] wire)"),
                "HTTP response parser must move to native liboresnet");
        assertFalse(source.contains("decodeChunked(byte[] body)"),
                "chunk framing must move to native liboresnet");
        assertFalse(source.contains("encodeRequest(HttpRequestValue"),
                "HTTP request serialization must move to native liboresnet");
        assertFalse(source.contains("resolveRedirect(String base"),
                "redirect URI resolution must move to native liboresnet");
    }


    @Test
    void nativeNetBuiltinIsCapabilityGatedWithoutGeneralNativeAuthority() throws Exception {
        String denied = """
                define module app
                  pub fnc main() => void {
                    native_net.resolve_all("localhost");
                    return;
                  }
                end
                """;
        Exception error = assertThrows(Exception.class, () -> evaluateWithoutNetwork(denied));
        assertTrue(error.toString().contains("NETWORK"));
    }

    @Test
    void pureOreslangNetStdlibFilesRemainFreeOfJavaTransportDependencies() throws Exception {
        String nativeApi = java.nio.file.Files.readString(
                java.nio.file.Path.of("stdlib/net/native.ores"));
        String http = java.nio.file.Files.readString(
                java.nio.file.Path.of("stdlib/net/http.ores"));
        assertTrue(nativeApi.contains("native_net."));
        assertFalse(nativeApi.contains("java:"));
        assertFalse(http.contains("java:"));
        assertFalse(http.contains("java.net"));
        assertFalse(http.contains("java.net.http"));
    }

    private static String evaluateWithoutNetwork(String program) throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withoutCapabilities(IsolatePolicy.Capability.NETWORK);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "native-net-denied-test.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = policy.restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }


    @Test
    void nativeNetRejectsNumericFdAtCompileTime() {
        String program = """
                define module app
                  pub fnc main() => void {
                    val bytes = [0, 0, 0, 0];
                    native_net.read(0, bytes, 3, 1);
                    return;
                  }
                end
                """;
        Exception error = assertThrows(
                Exception.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(
                error.toString().contains("NativeSocketHandle")
                        || error.toString().contains("Borrow"),
                error::toString);
    }

    @Test
    void nativeCloseImplementationNeverRetriesEintr() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/c/oresnet.c"));
        assertTrue(source.contains("close(fd) < 0 && errno != EINTR"));
        assertFalse(source.contains("if (errno == EINTR) continue;\n        throw_errno(env, \"close\")"));
    }

    private static String evaluate(String program) throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "native-net-test.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = policy.restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void readExactly(NativeSocketHandle handle, byte[] target) throws Exception {
        int offset = 0;
        while (offset < target.length) {
            int count = NativeSocketBridge.read(handle, target, offset, target.length - offset);
            if (count < 0) throw new AssertionError("unexpected EOF");
            offset += count;
        }
    }

    private static void writeAll(NativeSocketHandle handle, byte[] bytes) throws Exception {
        int offset = 0;
        while (offset < bytes.length) {
            int count = NativeSocketBridge.write(handle, bytes, offset, bytes.length - offset);
            if (count <= 0) throw new AssertionError("native send returned " + count);
            offset += count;
        }
    }

    private static void readHeaders(NativeSocketHandle handle) throws Exception {
        readHeadersText(handle);
    }

    private static String readHeadersText(NativeSocketHandle handle) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        while (bytes.size() < 64 * 1024) {
            int count = NativeSocketBridge.read(handle, one, 0, 1);
            if (count < 0) throw new AssertionError("EOF before request headers");
            bytes.write(one[0]);
            byte[] current = bytes.toByteArray();
            int n = current.length;
            if (n >= 4
                    && current[n - 4] == '\r'
                    && current[n - 3] == '\n'
                    && current[n - 2] == '\r'
                    && current[n - 1] == '\n') {
                return new String(current, StandardCharsets.ISO_8859_1);
            }
        }
        throw new AssertionError("request headers too large");
    }

    private static int headerInt(String headers, String name) {
        for (String line : headers.split("\\r\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        throw new AssertionError("missing HTTP header " + name);
    }

    private static NativeSocketHandle connectEventually(
            String host,
            int port,
            int timeoutMillis) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return NativeSocketBridge.connectHandle(host, port, timeoutMillis);
            } catch (Exception error) {
                last = error;
                Thread.sleep(20);
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("connect failed without an exception");
    }
}
