package dev.oreslang.net;

import dev.oreslang.runtime.BuiltinCallable;
import dev.oreslang.runtime.BuiltinValue;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Oreslang native networking package.
 *
 * The public guest surface intentionally mirrors the useful Java java.net and
 * java.net.http shapes, while transport I/O is implemented by liboresnet via
 * JNI rather than java.net.Socket/ServerSocket/HttpClient.
 */
public final class OresNet {
    private OresNet() { }

    public static BuiltinValue netPackage(OresContext context) {
        return new NetPackage(context);
    }

    public static BuiltinValue httpPackage(OresContext context) {
        return new HttpPackage(context);
    }

    private abstract static class NetworkValue implements BuiltinValue {
        final OresContext context;

        NetworkValue(OresContext context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        final void requireNetwork(String api) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK, api);
        }
    }

    private static final class NetPackage extends NetworkValue {
        NetPackage(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "Socket" -> new SocketFactory(context);
                case "ServerSocket" -> new ServerSocketFactory(context);
                case "InetAddress" -> new InetAddressFactory(context);
                case "InetSocketAddress" -> new InetSocketAddressFactory(context);
                case "URI" -> new UriFactory(context);
                case "http" -> new HttpPackage(context);
                default -> throw unknown("net", name);
            };
        }
    }

    private static final class SocketFactory extends NetworkValue {
        SocketFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            if (!name.equals("new")) throw unknown("net.Socket", name);
            return (BuiltinCallable) args -> {
                requireNetwork("net.Socket.new");
                if (args.isEmpty()) return SocketValue.unconnected(context);
                if (args.size() == 2) {
                    return SocketValue.connect(
                            context,
                            stringArg(args, 0, "net.Socket.new"),
                            intArg(args, 1, "net.Socket.new"),
                            0);
                }
                throw arity("net.Socket.new", "0 or 2", args.size());
            };
        }
    }

    private static final class ServerSocketFactory extends NetworkValue {
        ServerSocketFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            if (!name.equals("new")) throw unknown("net.ServerSocket", name);
            return (BuiltinCallable) args -> {
                requireNetwork("net.ServerSocket.new");
                if (args.isEmpty()) return ServerSocketValue.unbound(context);
                if (args.size() == 1) {
                    return ServerSocketValue.bound(context, "", intArg(args, 0, "net.ServerSocket.new"), 50);
                }
                if (args.size() == 2) {
                    return ServerSocketValue.bound(
                            context, "", intArg(args, 0, "net.ServerSocket.new"),
                            intArg(args, 1, "net.ServerSocket.new"));
                }
                if (args.size() == 3) {
                    InetSocketAddressValue bind = addressArg(args, 2, "net.ServerSocket.new");
                    return ServerSocketValue.bound(
                            context, bind.host, intArg(args, 0, "net.ServerSocket.new"),
                            intArg(args, 1, "net.ServerSocket.new"));
                }
                throw arity("net.ServerSocket.new", "0, 1, 2, or 3", args.size());
            };
        }
    }

    private static final class InetAddressFactory extends NetworkValue {
        InetAddressFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "getByName" -> (BuiltinCallable) args -> {
                    requireNetwork("net.InetAddress.getByName");
                    requireArity(args, 1, "net.InetAddress.getByName");
                    String host = stringArg(args, 0, "net.InetAddress.getByName");
                    String[] values = io(() -> NativeSocketBridge.resolveAll(host));
                    if (values.length == 0) throw new IllegalStateException("DNS returned no addresses for " + host);
                    return new InetAddressValue(context, host, values[0]);
                };
                case "getAllByName" -> (BuiltinCallable) args -> {
                    requireNetwork("net.InetAddress.getAllByName");
                    requireArity(args, 1, "net.InetAddress.getAllByName");
                    String host = stringArg(args, 0, "net.InetAddress.getAllByName");
                    String[] values = io(() -> NativeSocketBridge.resolveAll(host));
                    ArrayList<Object> result = new ArrayList<>(values.length);
                    for (String value : values) result.add(new InetAddressValue(context, host, value));
                    return result;
                };
                default -> throw unknown("net.InetAddress", name);
            };
        }
    }

    private static final class InetSocketAddressFactory extends NetworkValue {
        InetSocketAddressFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            if (!name.equals("new") && !name.equals("createUnresolved")) {
                throw unknown("net.InetSocketAddress", name);
            }
            return (BuiltinCallable) args -> {
                requireArity(args, 2, "net.InetSocketAddress." + name);
                return new InetSocketAddressValue(
                        context,
                        stringArg(args, 0, "net.InetSocketAddress." + name),
                        intArg(args, 1, "net.InetSocketAddress." + name));
            };
        }
    }

    private static final class UriFactory extends NetworkValue {
        UriFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            if (!name.equals("create")) throw unknown("net.URI", name);
            return (BuiltinCallable) args -> {
                requireArity(args, 1, "net.URI.create");
                return new UriValue(context, ParsedUri.parse(stringArg(args, 0, "net.URI.create")));
            };
        }
    }

    private static final class InetAddressValue extends NetworkValue {
        private final String hostName;
        private final String hostAddress;

        InetAddressValue(OresContext context, String hostName, String hostAddress) {
            super(context);
            this.hostName = hostName;
            this.hostAddress = hostAddress;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "getHostName", "getCanonicalHostName" ->
                        (BuiltinCallable) args -> { requireArity(args, 0, "InetAddress." + name); return hostName; };
                case "getHostAddress" ->
                        (BuiltinCallable) args -> { requireArity(args, 0, "InetAddress.getHostAddress"); return hostAddress; };
                case "isAnyLocalAddress" ->
                        (BuiltinCallable) args -> {
                            requireArity(args, 0, "InetAddress.isAnyLocalAddress");
                            return hostAddress.equals("0.0.0.0") || hostAddress.equals("::");
                        };
                case "isLoopbackAddress" ->
                        (BuiltinCallable) args -> {
                            requireArity(args, 0, "InetAddress.isLoopbackAddress");
                            return hostAddress.equals("::1") || hostAddress.startsWith("127.");
                        };
                case "toString" ->
                        (BuiltinCallable) args -> {
                            requireArity(args, 0, "InetAddress.toString");
                            return hostName + "/" + hostAddress;
                        };
                default -> throw unknown("InetAddress", name);
            };
        }
    }

    private static final class InetSocketAddressValue extends NetworkValue {
        private final String host;
        private final int port;

        InetSocketAddressValue(OresContext context, String host, int port) {
            super(context);
            checkPort(port, "InetSocketAddress");
            this.host = host;
            this.port = port;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "getHostString", "getHostName" ->
                        (BuiltinCallable) args -> { requireArity(args, 0, "InetSocketAddress." + name); return host; };
                case "getPort" ->
                        (BuiltinCallable) args -> { requireArity(args, 0, "InetSocketAddress.getPort"); return (long) port; };
                case "getAddress" ->
                        (BuiltinCallable) args -> {
                            requireArity(args, 0, "InetSocketAddress.getAddress");
                            requireNetwork("InetSocketAddress.getAddress");
                            String[] values = io(() -> NativeSocketBridge.resolveAll(host));
                            return values.length == 0 ? OresNull.INSTANCE : new InetAddressValue(context, host, values[0]);
                        };
                case "isUnresolved" ->
                        (BuiltinCallable) args -> {
                            requireArity(args, 0, "InetSocketAddress.isUnresolved");
                            return false;
                        };
                case "toString" ->
                        (BuiltinCallable) args -> {
                            requireArity(args, 0, "InetSocketAddress.toString");
                            return host + ":" + port;
                        };
                default -> throw unknown("InetSocketAddress", name);
            };
        }
    }

    private static final class SocketValue extends NetworkValue {
        private long fd;
        private boolean connected;
        private boolean inputShutdown;
        private boolean outputShutdown;
        private boolean closed;

        private SocketValue(OresContext context, long fd, boolean connected) {
            super(context);
            this.fd = fd;
            this.connected = connected;
        }

        static SocketValue unconnected(OresContext context) {
            return new SocketValue(context, -1, false);
        }

        static SocketValue connect(OresContext context, String host, int port, int timeoutMillis) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK, "net.Socket.connect");
            checkPort(port, "net.Socket.connect");
            long fd = io(() -> NativeSocketBridge.connect(host, port, timeoutMillis));
            return new SocketValue(context, fd, true);
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "connect" -> (BuiltinCallable) this::connect;
                case "getInputStream" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getInputStream");
                    ensureConnected();
                    return new SocketInputValue(context, this);
                };
                case "getOutputStream" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getOutputStream");
                    ensureConnected();
                    return new SocketOutputValue(context, this);
                };
                case "close" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.close");
                    close();
                    return OresNull.INSTANCE;
                };
                case "shutdownInput" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.shutdownInput");
                    ensureConnected();
                    requireNetwork("Socket.shutdownInput");
                    ioVoid(() -> NativeSocketBridge.shutdownInput(fd));
                    inputShutdown = true;
                    return OresNull.INSTANCE;
                };
                case "shutdownOutput" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.shutdownOutput");
                    ensureConnected();
                    requireNetwork("Socket.shutdownOutput");
                    ioVoid(() -> NativeSocketBridge.shutdownOutput(fd));
                    outputShutdown = true;
                    return OresNull.INSTANCE;
                };
                case "isConnected" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.isConnected");
                    return connected;
                };
                case "isClosed" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.isClosed");
                    return closed;
                };
                case "isInputShutdown" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.isInputShutdown");
                    return inputShutdown;
                };
                case "isOutputShutdown" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.isOutputShutdown");
                    return outputShutdown;
                };
                case "getInetAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getInetAddress");
                    ensureConnected();
                    String address = io(() -> NativeSocketBridge.remoteAddress(fd));
                    return new InetAddressValue(context, address, address);
                };
                case "getLocalAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getLocalAddress");
                    ensureConnected();
                    String address = io(() -> NativeSocketBridge.localAddress(fd));
                    return new InetAddressValue(context, address, address);
                };
                case "getPort" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getPort");
                    ensureConnected();
                    return (long) io(() -> NativeSocketBridge.remotePort(fd));
                };
                case "getLocalPort" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getLocalPort");
                    ensureConnected();
                    return (long) io(() -> NativeSocketBridge.localPort(fd));
                };
                case "getRemoteSocketAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getRemoteSocketAddress");
                    ensureConnected();
                    return new InetSocketAddressValue(
                            context,
                            io(() -> NativeSocketBridge.remoteAddress(fd)),
                            io(() -> NativeSocketBridge.remotePort(fd)));
                };
                case "getLocalSocketAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Socket.getLocalSocketAddress");
                    ensureConnected();
                    return new InetSocketAddressValue(
                            context,
                            io(() -> NativeSocketBridge.localAddress(fd)),
                            io(() -> NativeSocketBridge.localPort(fd)));
                };
                case "setTcpNoDelay" -> boolSetter("Socket.setTcpNoDelay", value -> NativeSocketBridge.setTcpNoDelay(fd, value));
                case "getTcpNoDelay" -> boolGetter("Socket.getTcpNoDelay", () -> NativeSocketBridge.getTcpNoDelay(fd));
                case "setKeepAlive" -> boolSetter("Socket.setKeepAlive", value -> NativeSocketBridge.setKeepAlive(fd, value));
                case "getKeepAlive" -> boolGetter("Socket.getKeepAlive", () -> NativeSocketBridge.getKeepAlive(fd));
                case "setReuseAddress" -> boolSetter("Socket.setReuseAddress", value -> NativeSocketBridge.setReuseAddress(fd, value));
                case "getReuseAddress" -> boolGetter("Socket.getReuseAddress", () -> NativeSocketBridge.getReuseAddress(fd));
                case "setReceiveBufferSize" -> intSetter("Socket.setReceiveBufferSize", value -> NativeSocketBridge.setReceiveBufferSize(fd, value));
                case "getReceiveBufferSize" -> intGetter("Socket.getReceiveBufferSize", () -> NativeSocketBridge.getReceiveBufferSize(fd));
                case "setSendBufferSize" -> intSetter("Socket.setSendBufferSize", value -> NativeSocketBridge.setSendBufferSize(fd, value));
                case "getSendBufferSize" -> intGetter("Socket.getSendBufferSize", () -> NativeSocketBridge.getSendBufferSize(fd));
                case "setSoTimeout" -> intSetter("Socket.setSoTimeout", value -> NativeSocketBridge.setSoTimeout(fd, value));
                case "getSoTimeout" -> intGetter("Socket.getSoTimeout", () -> NativeSocketBridge.getSoTimeout(fd));
                case "setPerformancePreferences" -> (BuiltinCallable) args -> {
                    requireArity(args, 3, "Socket.setPerformancePreferences");
                    return OresNull.INSTANCE;
                };
                default -> throw unknown("Socket", name);
            };
        }

        private Object connect(List<Object> args) {
            requireNetwork("Socket.connect");
            if (connected || closed) throw new IllegalStateException("socket is already connected or closed");
            InetSocketAddressValue endpoint;
            int timeout = 0;
            if (args.size() == 1 || args.size() == 2) {
                endpoint = addressArg(args, 0, "Socket.connect");
                if (args.size() == 2) timeout = intArg(args, 1, "Socket.connect");
            } else {
                throw arity("Socket.connect", "1 or 2", args.size());
            }
            final int connectTimeout = timeout;
            fd = io(() -> NativeSocketBridge.connect(endpoint.host, endpoint.port, connectTimeout));
            connected = true;
            return OresNull.INSTANCE;
        }

        private BuiltinCallable boolSetter(String api, IOBooleanConsumer setter) {
            return args -> {
                requireArity(args, 1, api);
                ensureConnected();
                requireNetwork(api);
                boolean value = boolArg(args, 0, api);
                ioVoid(() -> setter.accept(value));
                return OresNull.INSTANCE;
            };
        }

        private BuiltinCallable boolGetter(String api, IOBooleanSupplier getter) {
            return args -> {
                requireArity(args, 0, api);
                ensureConnected();
                requireNetwork(api);
                return io(getter::get);
            };
        }

        private BuiltinCallable intSetter(String api, IOIntConsumer setter) {
            return args -> {
                requireArity(args, 1, api);
                ensureConnected();
                requireNetwork(api);
                int value = intArg(args, 0, api);
                ioVoid(() -> setter.accept(value));
                return OresNull.INSTANCE;
            };
        }

        private BuiltinCallable intGetter(String api, IOIntSupplier getter) {
            return args -> {
                requireArity(args, 0, api);
                ensureConnected();
                requireNetwork(api);
                return (long) io(getter::get);
            };
        }

        private void ensureConnected() {
            if (!connected || closed || fd < 0) throw new IllegalStateException("socket is not connected");
        }

        private void close() {
            if (closed) return;
            requireNetwork("Socket.close");
            if (fd >= 0) ioVoid(() -> NativeSocketBridge.close(fd));
            fd = -1;
            closed = true;
        }
    }

    private static final class SocketInputValue extends NetworkValue {
        private final SocketValue socket;

        SocketInputValue(OresContext context, SocketValue socket) {
            super(context);
            this.socket = socket;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "read" -> (BuiltinCallable) this::read;
                case "available" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "InputStream.available");
                    socket.ensureConnected();
                    requireNetwork("InputStream.available");
                    return (long) io(() -> NativeSocketBridge.available(socket.fd));
                };
                case "close" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "InputStream.close");
                    socket.close();
                    return OresNull.INSTANCE;
                };
                default -> throw unknown("InputStream", name);
            };
        }

        private Object read(List<Object> args) {
            socket.ensureConnected();
            requireNetwork("InputStream.read");
            if (args.isEmpty()) {
                byte[] one = new byte[1];
                int count = io(() -> NativeSocketBridge.read(socket.fd, one, 0, 1));
                return count < 0 ? -1L : (long) (one[0] & 0xff);
            }

            if (args.size() != 1 && args.size() != 3) {
                throw arity("InputStream.read", "0, 1, or 3", args.size());
            }
            List<Object> target = mutableByteList(args.getFirst(), "InputStream.read");
            int offset = args.size() == 3 ? intArg(args, 1, "InputStream.read") : 0;
            int length = args.size() == 3 ? intArg(args, 2, "InputStream.read") : target.size();
            checkRange(target.size(), offset, length, "InputStream.read");

            byte[] bytes = new byte[target.size()];
            int count = io(() -> NativeSocketBridge.read(socket.fd, bytes, offset, length));
            if (count > 0) {
                for (int i = 0; i < count; i++) {
                    target.set(offset + i, (long) (bytes[offset + i] & 0xff));
                }
            }
            return (long) count;
        }
    }

    private static final class SocketOutputValue extends NetworkValue {
        private final SocketValue socket;

        SocketOutputValue(OresContext context, SocketValue socket) {
            super(context);
            this.socket = socket;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "write" -> (BuiltinCallable) this::write;
                case "writeString" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "OutputStream.writeString");
                    writeAll(stringArg(args, 0, "OutputStream.writeString").getBytes(StandardCharsets.UTF_8));
                    return OresNull.INSTANCE;
                };
                case "flush" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "OutputStream.flush");
                    return OresNull.INSTANCE;
                };
                case "close" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "OutputStream.close");
                    socket.close();
                    return OresNull.INSTANCE;
                };
                default -> throw unknown("OutputStream", name);
            };
        }

        private Object write(List<Object> args) {
            socket.ensureConnected();
            requireNetwork("OutputStream.write");
            if (args.size() == 1 && args.getFirst() instanceof Number number) {
                writeAll(new byte[] {(byte) (number.intValue() & 0xff)});
                return OresNull.INSTANCE;
            }
            if (args.size() != 1 && args.size() != 3) {
                throw arity("OutputStream.write", "1 or 3", args.size());
            }
            byte[] bytes = bytesArg(args.getFirst(), "OutputStream.write");
            int offset = args.size() == 3 ? intArg(args, 1, "OutputStream.write") : 0;
            int length = args.size() == 3 ? intArg(args, 2, "OutputStream.write") : bytes.length;
            checkRange(bytes.length, offset, length, "OutputStream.write");
            writeAll(bytes, offset, length);
            return OresNull.INSTANCE;
        }

        private void writeAll(byte[] bytes) {
            writeAll(bytes, 0, bytes.length);
        }

        private void writeAll(byte[] bytes, int offset, int length) {
            int written = 0;
            while (written < length) {
                int at = offset + written;
                int remaining = length - written;
                int count = io(() -> NativeSocketBridge.write(socket.fd, bytes, at, remaining));
                if (count <= 0) throw new IllegalStateException("native send returned " + count);
                written += count;
            }
        }
    }

    private static final class ServerSocketValue extends NetworkValue {
        private long fd;
        private boolean bound;
        private boolean closed;

        private ServerSocketValue(OresContext context, long fd, boolean bound) {
            super(context);
            this.fd = fd;
            this.bound = bound;
        }

        static ServerSocketValue unbound(OresContext context) {
            return new ServerSocketValue(context, -1, false);
        }

        static ServerSocketValue bound(OresContext context, String host, int port, int backlog) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK, "net.ServerSocket.bind");
            checkPort(port, "net.ServerSocket.bind");
            long fd = io(() -> NativeSocketBridge.listen(host, port, backlog, true));
            return new ServerSocketValue(context, fd, true);
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "bind" -> (BuiltinCallable) this::bind;
                case "accept" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.accept");
                    ensureBound();
                    requireNetwork("ServerSocket.accept");
                    long accepted = io(() -> NativeSocketBridge.accept(fd));
                    return new SocketValue(context, accepted, true);
                };
                case "close" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.close");
                    close();
                    return OresNull.INSTANCE;
                };
                case "isBound" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.isBound");
                    return bound;
                };
                case "isClosed" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.isClosed");
                    return closed;
                };
                case "getInetAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.getInetAddress");
                    ensureBound();
                    String address = io(() -> NativeSocketBridge.localAddress(fd));
                    return new InetAddressValue(context, address, address);
                };
                case "getLocalPort" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.getLocalPort");
                    ensureBound();
                    return (long) io(() -> NativeSocketBridge.localPort(fd));
                };
                case "getLocalSocketAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.getLocalSocketAddress");
                    ensureBound();
                    return new InetSocketAddressValue(
                            context,
                            io(() -> NativeSocketBridge.localAddress(fd)),
                            io(() -> NativeSocketBridge.localPort(fd)));
                };
                case "setReuseAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "ServerSocket.setReuseAddress");
                    ensureBound();
                    ioVoid(() -> NativeSocketBridge.setReuseAddress(fd, boolArg(args, 0, "ServerSocket.setReuseAddress")));
                    return OresNull.INSTANCE;
                };
                case "getReuseAddress" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.getReuseAddress");
                    ensureBound();
                    return io(() -> NativeSocketBridge.getReuseAddress(fd));
                };
                case "setReceiveBufferSize" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "ServerSocket.setReceiveBufferSize");
                    ensureBound();
                    ioVoid(() -> NativeSocketBridge.setReceiveBufferSize(fd, intArg(args, 0, "ServerSocket.setReceiveBufferSize")));
                    return OresNull.INSTANCE;
                };
                case "getReceiveBufferSize" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.getReceiveBufferSize");
                    ensureBound();
                    return (long) io(() -> NativeSocketBridge.getReceiveBufferSize(fd));
                };
                case "setSoTimeout" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "ServerSocket.setSoTimeout");
                    ensureBound();
                    ioVoid(() -> NativeSocketBridge.setSoTimeout(fd, intArg(args, 0, "ServerSocket.setSoTimeout")));
                    return OresNull.INSTANCE;
                };
                case "getSoTimeout" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "ServerSocket.getSoTimeout");
                    ensureBound();
                    return (long) io(() -> NativeSocketBridge.getSoTimeout(fd));
                };
                default -> throw unknown("ServerSocket", name);
            };
        }

        private Object bind(List<Object> args) {
            requireNetwork("ServerSocket.bind");
            if (bound || closed) throw new IllegalStateException("server socket is already bound or closed");
            if (args.size() != 1 && args.size() != 2) throw arity("ServerSocket.bind", "1 or 2", args.size());
            InetSocketAddressValue endpoint = addressArg(args, 0, "ServerSocket.bind");
            int backlog = args.size() == 2 ? intArg(args, 1, "ServerSocket.bind") : 50;
            fd = io(() -> NativeSocketBridge.listen(endpoint.host, endpoint.port, backlog, true));
            bound = true;
            return OresNull.INSTANCE;
        }

        private void ensureBound() {
            if (!bound || closed || fd < 0) throw new IllegalStateException("server socket is not bound");
        }

        private void close() {
            if (closed) return;
            requireNetwork("ServerSocket.close");
            if (fd >= 0) ioVoid(() -> NativeSocketBridge.close(fd));
            fd = -1;
            closed = true;
        }
    }

    // ---------------------------------------------------------------------
    // java.net.http-compatible surface, backed by the JNI TCP transport.
    // ---------------------------------------------------------------------

    private static final class HttpPackage extends NetworkValue {
        HttpPackage(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "HttpClient" -> new HttpClientFactory(context);
                case "HttpRequest" -> new HttpRequestFactory(context);
                case "HttpResponse" -> new HttpResponseFactory(context);
                case "HttpHeaders" -> new HttpHeadersFactory(context);
                case "BodyPublishers" -> new BodyPublishers(context);
                case "BodyHandlers" -> new BodyHandlers(context);
                default -> throw unknown("net.http", name);
            };
        }
    }

    private static final class HttpClientFactory extends NetworkValue {
        HttpClientFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "newHttpClient" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpClient.newHttpClient");
                    return new HttpClientValue(context, HttpVersion.HTTP_1_1, Redirect.NEVER, 0);
                };
                case "newBuilder" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpClient.newBuilder");
                    return new HttpClientBuilder(context);
                };
                case "Version" -> new EnumNamespace(Map.of(
                        "HTTP_1_1", HttpVersion.HTTP_1_1.name(),
                        "HTTP_2", HttpVersion.HTTP_2.name()));
                case "Redirect" -> new EnumNamespace(Map.of(
                        "NEVER", Redirect.NEVER.name(),
                        "NORMAL", Redirect.NORMAL.name(),
                        "ALWAYS", Redirect.ALWAYS.name()));
                default -> throw unknown("HttpClient", name);
            };
        }
    }

    private static final class HttpClientBuilder extends NetworkValue {
        private HttpVersion version = HttpVersion.HTTP_1_1;
        private Redirect redirects = Redirect.NEVER;
        private int connectTimeoutMillis;

        HttpClientBuilder(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "version" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpClient.Builder.version");
                    version = parseVersion(args.getFirst());
                    return this;
                };
                case "followRedirects" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpClient.Builder.followRedirects");
                    redirects = parseRedirect(args.getFirst());
                    return this;
                };
                case "connectTimeout" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpClient.Builder.connectTimeout");
                    connectTimeoutMillis = nonNegative(intArg(args, 0, "HttpClient.Builder.connectTimeout"), "connectTimeout");
                    return this;
                };
                case "build" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpClient.Builder.build");
                    return new HttpClientValue(context, version, redirects, connectTimeoutMillis);
                };
                case "proxy", "authenticator", "cookieHandler", "executor", "sslContext", "sslParameters", "priority" ->
                        unsupportedCallable("HttpClient.Builder." + name, "not implemented by the native HTTP/1.1 backend yet");
                default -> throw unknown("HttpClient.Builder", name);
            };
        }
    }

    private static final class HttpRequestFactory extends NetworkValue {
        HttpRequestFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "newBuilder" -> (BuiltinCallable) args -> {
                    if (args.size() > 1) throw arity("HttpRequest.newBuilder", "0 or 1", args.size());
                    HttpRequestBuilder builder = new HttpRequestBuilder(context);
                    if (args.size() == 1) builder.uri = uriString(args.getFirst(), "HttpRequest.newBuilder");
                    return builder;
                };
                case "BodyPublishers" -> new BodyPublishers(context);
                default -> throw unknown("HttpRequest", name);
            };
        }
    }

    private static final class HttpResponseFactory extends NetworkValue {
        HttpResponseFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "BodyHandlers" -> new BodyHandlers(context);
                default -> throw unknown("HttpResponse", name);
            };
        }
    }

    private static final class HttpHeadersFactory extends NetworkValue {
        HttpHeadersFactory(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            if (!name.equals("of")) throw unknown("HttpHeaders", name);
            return unsupportedCallable("HttpHeaders.of", "construct response/request headers through HttpRequest.Builder for now");
        }
    }

    private static final class BodyPublishers extends NetworkValue {
        BodyPublishers(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "noBody" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "BodyPublishers.noBody");
                    return new BodyPublisherValue(new byte[0]);
                };
                case "ofString" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "BodyPublishers.ofString");
                    return new BodyPublisherValue(stringArg(args, 0, "BodyPublishers.ofString").getBytes(StandardCharsets.UTF_8));
                };
                case "ofByteArray" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "BodyPublishers.ofByteArray");
                    return new BodyPublisherValue(bytesArg(args.getFirst(), "BodyPublishers.ofByteArray"));
                };
                case "ofFile", "ofInputStream", "fromPublisher", "concat" ->
                        unsupportedCallable("BodyPublishers." + name, "streaming publishers are not implemented yet");
                default -> throw unknown("BodyPublishers", name);
            };
        }
    }

    private static final class BodyPublisherValue implements BuiltinValue {
        private final byte[] bytes;

        BodyPublisherValue(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "contentLength" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "BodyPublisher.contentLength");
                    return (long) bytes.length;
                };
                default -> throw unknown("BodyPublisher", name);
            };
        }
    }

    private static final class BodyHandlers extends NetworkValue {
        BodyHandlers(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "ofString" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "BodyHandlers.ofString");
                    return new BodyHandlerValue(BodyKind.STRING);
                };
                case "ofByteArray" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "BodyHandlers.ofByteArray");
                    return new BodyHandlerValue(BodyKind.BYTE_ARRAY);
                };
                case "discarding" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "BodyHandlers.discarding");
                    return new BodyHandlerValue(BodyKind.DISCARD);
                };
                case "ofFile", "ofInputStream", "ofLines", "fromSubscriber", "buffering", "replacing" ->
                        unsupportedCallable("BodyHandlers." + name, "streaming/file handlers are not implemented yet");
                default -> throw unknown("BodyHandlers", name);
            };
        }
    }

    private enum BodyKind { STRING, BYTE_ARRAY, DISCARD }

    private record BodyHandlerValue(BodyKind kind) { }

    private static final class HttpRequestBuilder extends NetworkValue {
        private String uri;
        private String method = "GET";
        private byte[] body = new byte[0];
        private final LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        private int timeoutMillis;
        private boolean expectContinue;
        private HttpVersion version = HttpVersion.HTTP_1_1;

        HttpRequestBuilder(OresContext context) { super(context); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "uri" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpRequest.Builder.uri");
                    uri = uriString(args.getFirst(), "HttpRequest.Builder.uri");
                    return this;
                };
                case "timeout" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpRequest.Builder.timeout");
                    timeoutMillis = nonNegative(intArg(args, 0, "HttpRequest.Builder.timeout"), "timeout");
                    return this;
                };
                case "expectContinue" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpRequest.Builder.expectContinue");
                    expectContinue = boolArg(args, 0, "HttpRequest.Builder.expectContinue");
                    return this;
                };
                case "version" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpRequest.Builder.version");
                    version = parseVersion(args.getFirst());
                    return this;
                };
                case "header" -> (BuiltinCallable) args -> {
                    requireArity(args, 2, "HttpRequest.Builder.header");
                    addHeader(
                            stringArg(args, 0, "HttpRequest.Builder.header"),
                            stringArg(args, 1, "HttpRequest.Builder.header"),
                            false);
                    return this;
                };
                case "setHeader" -> (BuiltinCallable) args -> {
                    requireArity(args, 2, "HttpRequest.Builder.setHeader");
                    addHeader(
                            stringArg(args, 0, "HttpRequest.Builder.setHeader"),
                            stringArg(args, 1, "HttpRequest.Builder.setHeader"),
                            true);
                    return this;
                };
                case "headers" -> (BuiltinCallable) args -> {
                    if (args.size() % 2 != 0) throw new IllegalArgumentException("HttpRequest.Builder.headers expects name/value pairs");
                    for (int i = 0; i < args.size(); i += 2) {
                        addHeader(
                                stringArg(args, i, "HttpRequest.Builder.headers"),
                                stringArg(args, i + 1, "HttpRequest.Builder.headers"),
                                false);
                    }
                    return this;
                };
                case "GET" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpRequest.Builder.GET");
                    method = "GET";
                    body = new byte[0];
                    return this;
                };
                case "DELETE" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpRequest.Builder.DELETE");
                    method = "DELETE";
                    body = new byte[0];
                    return this;
                };
                case "POST" -> methodWithPublisher("POST");
                case "PUT" -> methodWithPublisher("PUT");
                case "method" -> (BuiltinCallable) args -> {
                    requireArity(args, 2, "HttpRequest.Builder.method");
                    method = stringArg(args, 0, "HttpRequest.Builder.method").toUpperCase(Locale.ROOT);
                    body = publisherArg(args.get(1), "HttpRequest.Builder.method").bytes.clone();
                    return this;
                };
                case "build" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpRequest.Builder.build");
                    if (uri == null || uri.isBlank()) throw new IllegalStateException("HttpRequest requires a URI");
                    ParsedUri.parse(uri);
                    return snapshot();
                };
                case "copy" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "HttpRequest.Builder.copy");
                    HttpRequestBuilder copy = new HttpRequestBuilder(context);
                    copy.uri = uri;
                    copy.method = method;
                    copy.body = body.clone();
                    copy.timeoutMillis = timeoutMillis;
                    copy.expectContinue = expectContinue;
                    copy.version = version;
                    headers.forEach((key, values) -> copy.headers.put(key, new ArrayList<>(values)));
                    return copy;
                };
                default -> throw unknown("HttpRequest.Builder", name);
            };
        }

        private BuiltinCallable methodWithPublisher(String name) {
            return args -> {
                requireArity(args, 1, "HttpRequest.Builder." + name);
                method = name;
                body = publisherArg(args.getFirst(), "HttpRequest.Builder." + name).bytes.clone();
                return this;
            };
        }

        private void addHeader(String name, String value, boolean replace) {
            validateHeader(name, value);
            String key = canonicalHeaderName(name);
            if (replace) headers.put(key, new ArrayList<>(List.of(value)));
            else headers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }

        private HttpRequestValue snapshot() {
            LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            return new HttpRequestValue(
                    context, uri, method, body.clone(), Collections.unmodifiableMap(copy),
                    timeoutMillis, expectContinue, version);
        }
    }

    private static final class HttpRequestValue extends NetworkValue {
        private final String uri;
        private final String method;
        private final byte[] body;
        private final Map<String, List<String>> headers;
        private final int timeoutMillis;
        private final boolean expectContinue;
        private final HttpVersion version;

        HttpRequestValue(
                OresContext context,
                String uri,
                String method,
                byte[] body,
                Map<String, List<String>> headers,
                int timeoutMillis,
                boolean expectContinue,
                HttpVersion version) {
            super(context);
            this.uri = uri;
            this.method = method;
            this.body = body;
            this.headers = headers;
            this.timeoutMillis = timeoutMillis;
            this.expectContinue = expectContinue;
            this.version = version;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "uri" -> noArg("HttpRequest.uri", new UriValue(context, ParsedUri.parse(uri)));
                case "method" -> noArg("HttpRequest.method", method);
                case "headers" -> noArg("HttpRequest.headers", new HttpHeadersValue(context, headers));
                case "expectContinue" -> noArg("HttpRequest.expectContinue", expectContinue);
                case "timeout" -> noArg("HttpRequest.timeout", timeoutMillis == 0
                        ? new OptionalValue(OresNull.INSTANCE, false)
                        : new OptionalValue((long) timeoutMillis, true));
                case "version" -> noArg("HttpRequest.version", new OptionalValue(version.name(), true));
                default -> throw unknown("HttpRequest", name);
            };
        }
    }

    private static final class HttpClientValue extends NetworkValue {
        private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
        private final HttpVersion version;
        private final Redirect redirects;
        private final int connectTimeoutMillis;

        HttpClientValue(OresContext context, HttpVersion version, Redirect redirects, int connectTimeoutMillis) {
            super(context);
            this.version = version;
            this.redirects = redirects;
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "send" -> (BuiltinCallable) this::send;
                case "sendAsync" -> unsupportedCallable(
                        "HttpClient.sendAsync",
                        "async native I/O must be integrated with the Oreslang actor scheduler rather than a Java executor");
                case "version" -> noArg("HttpClient.version", version.name());
                case "followRedirects" -> noArg("HttpClient.followRedirects", redirects.name());
                case "connectTimeout" -> noArg(
                        "HttpClient.connectTimeout",
                        connectTimeoutMillis == 0
                                ? new OptionalValue(OresNull.INSTANCE, false)
                                : new OptionalValue((long) connectTimeoutMillis, true));
                case "proxy", "authenticator", "cookieHandler", "executor", "sslContext", "sslParameters" ->
                        noArg("HttpClient." + name, new OptionalValue(OresNull.INSTANCE, false));
                case "close", "shutdown", "shutdownNow", "awaitTermination" -> (BuiltinCallable) args -> {
                    if (!name.equals("awaitTermination")) requireArity(args, 0, "HttpClient." + name);
                    return name.equals("awaitTermination") ? true : OresNull.INSTANCE;
                };
                case "isTerminated" -> noArg("HttpClient.isTerminated", false);
                default -> throw unknown("HttpClient", name);
            };
        }

        private Object send(List<Object> args) {
            requireArity(args, 2, "HttpClient.send");
            if (!(args.getFirst() instanceof HttpRequestValue request)) {
                throw new IllegalArgumentException("HttpClient.send expects HttpRequest as argument 1");
            }
            if (!(args.get(1) instanceof BodyHandlerValue handler)) {
                throw new IllegalArgumentException("HttpClient.send expects BodyHandler as argument 2");
            }
            requireNetwork("HttpClient.send");
            if (version == HttpVersion.HTTP_2 || request.version == HttpVersion.HTTP_2) {
                throw new UnsupportedOperationException(
                        "native HTTP/2 is not implemented yet; use HttpClient.Version.HTTP_1_1");
            }
            return sendFollowingRedirects(request, handler, null, 0);
        }

        private HttpResponseValue sendFollowingRedirects(
                HttpRequestValue request,
                BodyHandlerValue handler,
                HttpResponseValue previous,
                int redirectCount) {
            HttpResponseValue response = sendOnce(request, handler, previous);
            if (redirects == Redirect.NEVER || redirectCount >= 5 || !isRedirect(response.statusCode)) return response;

            String location = response.headers.firstRaw("location");
            if (location == null) return response;

            String nextUri = resolveRedirect(request.uri, location);
            String nextMethod = request.method;
            byte[] nextBody = request.body;
            if (response.statusCode == 303
                    || ((response.statusCode == 301 || response.statusCode == 302)
                    && request.method.equals("POST"))) {
                nextMethod = "GET";
                nextBody = new byte[0];
            }

            HttpRequestValue next = new HttpRequestValue(
                    context, nextUri, nextMethod, nextBody, request.headers,
                    request.timeoutMillis, request.expectContinue, request.version);
            return sendFollowingRedirects(next, handler, response, redirectCount + 1);
        }

        private HttpResponseValue sendOnce(
                HttpRequestValue request,
                BodyHandlerValue handler,
                HttpResponseValue previous) {
            ParsedUri uri = ParsedUri.parse(request.uri);
            if (!uri.scheme.equals("http")) {
                if (uri.scheme.equals("https")) {
                    throw new UnsupportedOperationException(
                            "HTTPS requires the native TLS backend; java.net.http is intentionally not used as a fallback");
                }
                throw new IllegalArgumentException("unsupported URI scheme: " + uri.scheme);
            }

            int connectTimeout = connectTimeoutMillis;
            long fd = io(() -> NativeSocketBridge.connect(uri.host, uri.port, connectTimeout));
            try {
                if (request.timeoutMillis > 0) {
                    ioVoid(() -> NativeSocketBridge.setSoTimeout(fd, request.timeoutMillis));
                }

                byte[] wireRequest = encodeRequest(request, uri);
                writeAll(fd, wireRequest);
                byte[] wireResponse = readToEof(fd, MAX_RESPONSE_BYTES);
                ParsedResponse parsed = parseResponse(wireResponse);

                Object body = switch (handler.kind()) {
                    case STRING -> new String(parsed.body, StandardCharsets.UTF_8);
                    case BYTE_ARRAY -> bytesToList(parsed.body);
                    case DISCARD -> OresNull.INSTANCE;
                };

                return new HttpResponseValue(
                        context,
                        parsed.statusCode,
                        new HttpHeadersValue(context, parsed.headers),
                        body,
                        request,
                        previous,
                        request.uri,
                        HttpVersion.HTTP_1_1);
            } finally {
                ioVoid(() -> NativeSocketBridge.close(fd));
            }
        }

        private static byte[] encodeRequest(HttpRequestValue request, ParsedUri uri) {
            StringBuilder head = new StringBuilder();
            head.append(request.method).append(' ').append(uri.requestTarget()).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(uri.hostHeader()).append("\r\n");
            head.append("Connection: close\r\n");

            boolean hasContentType = false;
            for (Map.Entry<String, List<String>> entry : request.headers.entrySet()) {
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("content-length") || lower.equals("connection")) continue;
                if (lower.equals("content-type")) hasContentType = true;
                for (String value : entry.getValue()) {
                    head.append(entry.getKey()).append(": ").append(value).append("\r\n");
                }
            }

            if (request.body.length > 0 || request.method.equals("POST") || request.method.equals("PUT")) {
                head.append("Content-Length: ").append(request.body.length).append("\r\n");
                if (!hasContentType) head.append("Content-Type: application/octet-stream\r\n");
            }
            if (request.expectContinue) head.append("Expect: 100-continue\r\n");
            head.append("\r\n");

            byte[] header = head.toString().getBytes(StandardCharsets.ISO_8859_1);
            byte[] result = Arrays.copyOf(header, header.length + request.body.length);
            System.arraycopy(request.body, 0, result, header.length, request.body.length);
            return result;
        }

        private static void writeAll(long fd, byte[] bytes) {
            int offset = 0;
            while (offset < bytes.length) {
                int at = offset;
                int count = io(() -> NativeSocketBridge.write(fd, bytes, at, bytes.length - at));
                if (count <= 0) throw new IllegalStateException("native send returned " + count);
                offset += count;
            }
        }

        private static byte[] readToEof(long fd, int limit) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            while (true) {
                int count = io(() -> NativeSocketBridge.read(fd, buffer, 0, buffer.length));
                if (count < 0) break;
                if (out.size() + count > limit) {
                    throw new IllegalStateException("HTTP response exceeds " + limit + " bytes");
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static final class HttpResponseValue extends NetworkValue {
        private final int statusCode;
        private final HttpHeadersValue headers;
        private final Object body;
        private final HttpRequestValue request;
        private final HttpResponseValue previous;
        private final String uri;
        private final HttpVersion version;

        HttpResponseValue(
                OresContext context,
                int statusCode,
                HttpHeadersValue headers,
                Object body,
                HttpRequestValue request,
                HttpResponseValue previous,
                String uri,
                HttpVersion version) {
            super(context);
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.request = request;
            this.previous = previous;
            this.uri = uri;
            this.version = version;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "statusCode" -> noArg("HttpResponse.statusCode", (long) statusCode);
                case "headers" -> noArg("HttpResponse.headers", headers);
                case "body" -> noArg("HttpResponse.body", body);
                case "request" -> noArg("HttpResponse.request", request);
                case "previousResponse" -> noArg(
                        "HttpResponse.previousResponse",
                        previous == null
                                ? new OptionalValue(OresNull.INSTANCE, false)
                                : new OptionalValue(previous, true));
                case "uri" -> noArg("HttpResponse.uri", new UriValue(context, ParsedUri.parse(uri)));
                case "version" -> noArg("HttpResponse.version", version.name());
                case "sslSession" -> noArg("HttpResponse.sslSession", new OptionalValue(OresNull.INSTANCE, false));
                default -> throw unknown("HttpResponse", name);
            };
        }
    }

    private static final class HttpHeadersValue extends NetworkValue {
        private final Map<String, List<String>> values;

        HttpHeadersValue(OresContext context, Map<String, List<String>> source) {
            super(context);
            LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
            source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            this.values = Collections.unmodifiableMap(copy);
        }

        String firstRaw(String name) {
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "firstValue" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpHeaders.firstValue");
                    String value = firstRaw(stringArg(args, 0, "HttpHeaders.firstValue"));
                    return value == null
                            ? new OptionalValue(OresNull.INSTANCE, false)
                            : new OptionalValue(value, true);
                };
                case "firstValueAsLong" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpHeaders.firstValueAsLong");
                    String value = firstRaw(stringArg(args, 0, "HttpHeaders.firstValueAsLong"));
                    if (value == null) return new OptionalValue(OresNull.INSTANCE, false);
                    return new OptionalValue(Long.parseLong(value.trim()), true);
                };
                case "allValues" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "HttpHeaders.allValues");
                    String target = stringArg(args, 0, "HttpHeaders.allValues");
                    ArrayList<Object> result = new ArrayList<>();
                    values.forEach((key, items) -> {
                        if (key.equalsIgnoreCase(target)) result.addAll(items);
                    });
                    return result;
                };
                case "map" -> noArg("HttpHeaders.map", values);
                default -> throw unknown("HttpHeaders", name);
            };
        }
    }

    private static final class OptionalValue implements BuiltinValue {
        private final Object value;
        private final boolean present;

        OptionalValue(Object value, boolean present) {
            this.value = value;
            this.present = present;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "isPresent" -> noArg("Optional.isPresent", present);
                case "isEmpty" -> noArg("Optional.isEmpty", !present);
                case "get" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "Optional.get");
                    if (!present) throw new IllegalStateException("No value present");
                    return value;
                };
                case "orElse" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "Optional.orElse");
                    return present ? value : args.getFirst();
                };
                default -> throw unknown("Optional", name);
            };
        }
    }

    private static final class EnumNamespace implements BuiltinValue {
        private final Map<String, String> values;

        EnumNamespace(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public Object member(String name) {
            String value = values.get(name);
            if (value == null) throw unknown("enum", name);
            return value;
        }
    }

    private static final class UriValue extends NetworkValue {
        private final ParsedUri uri;

        UriValue(OresContext context, ParsedUri uri) {
            super(context);
            this.uri = uri;
        }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "getScheme" -> noArg("URI.getScheme", uri.scheme);
                case "getHost" -> noArg("URI.getHost", uri.host);
                case "getPort" -> noArg("URI.getPort", (long) uri.explicitPort);
                case "getPath" -> noArg("URI.getPath", uri.path);
                case "getQuery" -> noArg("URI.getQuery", uri.query == null ? OresNull.INSTANCE : uri.query);
                case "toString" -> noArg("URI.toString", uri.raw);
                default -> throw unknown("URI", name);
            };
        }
    }

    private enum HttpVersion { HTTP_1_1, HTTP_2 }
    private enum Redirect { NEVER, NORMAL, ALWAYS }

    private record ParsedUri(
            String raw,
            String scheme,
            String host,
            int explicitPort,
            int port,
            String path,
            String query) {

        static ParsedUri parse(String raw) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("URI cannot be blank");
            int schemeAt = raw.indexOf("://");
            if (schemeAt <= 0) throw new IllegalArgumentException("absolute URI required: " + raw);
            String scheme = raw.substring(0, schemeAt).toLowerCase(Locale.ROOT);
            int authorityStart = schemeAt + 3;
            int pathStart = raw.indexOf('/', authorityStart);
            int queryOnly = raw.indexOf('?', authorityStart);
            int authorityEnd;
            if (pathStart < 0) authorityEnd = queryOnly < 0 ? raw.length() : queryOnly;
            else if (queryOnly >= 0 && queryOnly < pathStart) authorityEnd = queryOnly;
            else authorityEnd = pathStart;

            String authority = raw.substring(authorityStart, authorityEnd);
            if (authority.isBlank()) throw new IllegalArgumentException("URI host is required: " + raw);

            String host;
            int explicitPort = -1;
            if (authority.startsWith("[")) {
                int closing = authority.indexOf(']');
                if (closing < 0) throw new IllegalArgumentException("invalid IPv6 URI authority: " + raw);
                host = authority.substring(1, closing);
                if (closing + 1 < authority.length()) {
                    if (authority.charAt(closing + 1) != ':') throw new IllegalArgumentException("invalid URI authority: " + raw);
                    explicitPort = Integer.parseInt(authority.substring(closing + 2));
                }
            } else {
                int colon = authority.lastIndexOf(':');
                if (colon > 0 && authority.indexOf(':') == colon) {
                    host = authority.substring(0, colon);
                    explicitPort = Integer.parseInt(authority.substring(colon + 1));
                } else {
                    host = authority;
                }
            }
            if (host.isBlank()) throw new IllegalArgumentException("URI host is required: " + raw);
            if (explicitPort != -1) checkPort(explicitPort, "URI");

            int defaultPort = scheme.equals("https") ? 443 : 80;
            int port = explicitPort == -1 ? defaultPort : explicitPort;

            String pathAndQuery = authorityEnd >= raw.length() ? "" : raw.substring(authorityEnd);
            String path;
            String query = null;
            int queryAt = pathAndQuery.indexOf('?');
            if (queryAt >= 0) {
                path = pathAndQuery.substring(0, queryAt);
                query = pathAndQuery.substring(queryAt + 1);
            } else {
                path = pathAndQuery;
            }
            if (path.isEmpty()) path = "/";
            return new ParsedUri(raw, scheme, host, explicitPort, port, path, query);
        }

        String requestTarget() {
            return query == null ? path : path + "?" + query;
        }

        String hostHeader() {
            boolean ipv6 = host.indexOf(':') >= 0;
            String rendered = ipv6 ? "[" + host + "]" : host;
            boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            return defaultPort ? rendered : rendered + ":" + port;
        }
    }

    private record ParsedResponse(
            int statusCode,
            Map<String, List<String>> headers,
            byte[] body) { }

    private static ParsedResponse parseResponse(byte[] wire) {
        int headerEnd = indexOf(wire, new byte[] {'\r','\n','\r','\n'}, 0);
        if (headerEnd < 0) throw new IllegalStateException("malformed HTTP response: missing header terminator");

        String head = new String(wire, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\\r\\n");
        if (lines.length == 0) throw new IllegalStateException("malformed HTTP response");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/")) {
            throw new IllegalStateException("malformed HTTP status line: " + lines[0]);
        }
        int statusCode = Integer.parseInt(status[1]);

        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new IllegalStateException("malformed HTTP header: " + lines[i]);
            String name = canonicalHeaderName(lines[i].substring(0, colon).trim());
            String value = lines[i].substring(colon + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }

        byte[] body = Arrays.copyOfRange(wire, headerEnd + 4, wire.length);
        String transferEncoding = firstHeader(headers, "transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = decodeChunked(body);
        } else {
            String contentLength = firstHeader(headers, "content-length");
            if (contentLength != null) {
                int length = Integer.parseInt(contentLength.trim());
                if (length < body.length) body = Arrays.copyOf(body, length);
                if (length > body.length) {
                    throw new IllegalStateException("truncated HTTP response body");
                }
            }
        }

        LinkedHashMap<String, List<String>> frozen = new LinkedHashMap<>();
        headers.forEach((key, values) -> frozen.put(key, List.copyOf(values)));
        return new ParsedResponse(statusCode, Collections.unmodifiableMap(frozen), body);
    }

    private static byte[] decodeChunked(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (true) {
            int lineEnd = indexOf(body, new byte[] {'\r','\n'}, offset);
            if (lineEnd < 0) throw new IllegalStateException("malformed chunked response");
            String sizeLine = new String(body, offset, lineEnd - offset, StandardCharsets.US_ASCII);
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) sizeLine = sizeLine.substring(0, semicolon);
            int size = Integer.parseInt(sizeLine.trim(), 16);
            offset = lineEnd + 2;
            if (size == 0) break;
            if (offset + size + 2 > body.length) throw new IllegalStateException("truncated chunked response");
            out.write(body, offset, size);
            offset += size;
            if (body[offset] != '\r' || body[offset + 1] != '\n') {
                throw new IllegalStateException("malformed chunk terminator");
            }
            offset += 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] source, byte[] needle, int start) {
        outer:
        for (int i = Math.max(0, start); i <= source.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (source[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String resolveRedirect(String base, String location) {
        if (location.contains("://")) return location;
        ParsedUri parsed = ParsedUri.parse(base);
        String origin = parsed.scheme + "://" + parsed.hostHeader();
        if (location.startsWith("/")) return origin + location;
        String basePath = parsed.path;
        int slash = basePath.lastIndexOf('/');
        String prefix = slash < 0 ? "/" : basePath.substring(0, slash + 1);
        return origin + prefix + location;
    }

    private static void validateHeader(String name, String value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("HTTP header name cannot be blank");
        if (value == null) throw new IllegalArgumentException("HTTP header value cannot be null");
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("HTTP headers cannot contain CR/LF");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("connection") || lower.equals("content-length") || lower.equals("expect")
                || lower.equals("host") || lower.equals("upgrade")) {
            throw new IllegalArgumentException("restricted HTTP request header: " + name);
        }
    }

    private static String canonicalHeaderName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static HttpVersion parseVersion(Object value) {
        String raw = String.valueOf(value).toUpperCase(Locale.ROOT).replace('.', '_');
        if (raw.equals("HTTP_1_1") || raw.equals("HTTP/1_1") || raw.equals("HTTP/1.1")) return HttpVersion.HTTP_1_1;
        if (raw.equals("HTTP_2") || raw.equals("HTTP/2")) return HttpVersion.HTTP_2;
        throw new IllegalArgumentException("unknown HTTP version " + value);
    }

    private static Redirect parseRedirect(Object value) {
        try {
            return Redirect.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown redirect policy " + value);
        }
    }

    private static String uriString(Object value, String api) {
        if (value instanceof String string) return string;
        if (value instanceof UriValue uri) return uri.uri.raw;
        throw new IllegalArgumentException(api + " expects a URI string or net.URI value");
    }

    private static BodyPublisherValue publisherArg(Object value, String api) {
        if (value instanceof BodyPublisherValue publisher) return publisher;
        throw new IllegalArgumentException(api + " expects an HttpRequest.BodyPublisher");
    }

    private static InetSocketAddressValue addressArg(List<Object> args, int index, String api) {
        Object value = args.get(index);
        if (value instanceof InetSocketAddressValue address) return address;
        throw new IllegalArgumentException(api + " expects net.InetSocketAddress at argument " + (index + 1));
    }

    private static BuiltinCallable noArg(String api, Object result) {
        return args -> {
            requireArity(args, 0, api);
            return result;
        };
    }

    private static BuiltinCallable unsupportedCallable(String api, String reason) {
        return args -> {
            throw new UnsupportedOperationException(api + ": " + reason);
        };
    }

    private static IllegalArgumentException unknown(String owner, String member) {
        return new IllegalArgumentException("unknown " + owner + " member " + member);
    }

    private static IllegalArgumentException arity(String api, String expected, int actual) {
        return new IllegalArgumentException(api + " expects " + expected + " argument(s), got " + actual);
    }

    private static void requireArity(List<Object> args, int expected, String api) {
        if (args.size() != expected) throw arity(api, Integer.toString(expected), args.size());
    }

    private static String stringArg(List<Object> args, int index, String api) {
        Object value = args.get(index);
        if (value instanceof String string) return string;
        throw new IllegalArgumentException(api + " argument " + (index + 1) + " must be String");
    }

    private static int intArg(List<Object> args, int index, String api) {
        Object value = args.get(index);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(api + " argument " + (index + 1) + " must be integer");
        }
        long longValue = number.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(api + " integer argument is out of range");
        }
        return (int) longValue;
    }

    private static boolean boolArg(List<Object> args, int index, String api) {
        Object value = args.get(index);
        if (value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException(api + " argument " + (index + 1) + " must be bool");
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static void checkPort(int port, String api) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException(api + " port must be between 0 and 65535");
    }

    private static void checkRange(int size, int offset, int length, String api) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new IllegalArgumentException(api + " byte range is out of bounds");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutableByteList(Object value, String api) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(api + " expects an array/list of bytes");
        }
        return (List<Object>) list;
    }

    private static byte[] bytesArg(Object value, String api) {
        if (value instanceof String string) return string.getBytes(StandardCharsets.UTF_8);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(api + " expects an array/list of byte values");
        }
        byte[] bytes = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Number number)) {
                throw new IllegalArgumentException(api + " byte at index " + i + " is not numeric");
            }
            int n = number.intValue();
            if (n < 0 || n > 255) throw new IllegalArgumentException(api + " byte at index " + i + " is outside 0..255");
            bytes[i] = (byte) n;
        }
        return bytes;
    }

    private static List<Object> bytesToList(byte[] bytes) {
        ArrayList<Object> result = new ArrayList<>(bytes.length);
        for (byte value : bytes) result.add((long) (value & 0xff));
        return result;
    }

    @FunctionalInterface private interface IOCall<T> { T run() throws IOException; }
    @FunctionalInterface private interface IOVoidCall { void run() throws IOException; }
    @FunctionalInterface private interface IOBooleanConsumer { void accept(boolean value) throws IOException; }
    @FunctionalInterface private interface IOBooleanSupplier { boolean get() throws IOException; }
    @FunctionalInterface private interface IOIntConsumer { void accept(int value) throws IOException; }
    @FunctionalInterface private interface IOIntSupplier { int get() throws IOException; }

    private static <T> T io(IOCall<T> call) {
        try {
            return call.run();
        } catch (IOException error) {
            throw new IllegalStateException("native network I/O failed: " + error.getMessage(), error);
        }
    }

    private static void ioVoid(IOVoidCall call) {
        try {
            call.run();
        } catch (IOException error) {
            throw new IllegalStateException("native network I/O failed: " + error.getMessage(), error);
        }
    }
}
