package dev.oreslang.net;

import dev.oreslang.runtime.BuiltinCallable;
import dev.oreslang.runtime.BuiltinValue;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Capability-gated primitive ABI between Oreslang stdlib code and liboresnet.
 *
 * <p>This class owns no HTTP, URI, socket policy, or protocol semantics. It
 * performs capability admission plus value marshaling only. Socket identity is
 * an opaque NativeSocketHandle; numeric OS file descriptors never become guest
 * values.</p>
 */
public final class NativeNetBuiltin implements BuiltinValue {
    private static final int MAX_READ_SOME_BYTES = 1024 * 1024;

    private final OresContext context;

    public NativeNetBuiltin(OresContext context) {
        this.context = context;
    }

    @Override
    public Object member(String name) {
        return switch (name) {
            case "connect" -> (BuiltinCallable) args -> {
                require(args, 3, name);
                admit(name);
                return io(() -> NativeSocketBridge.connectHandle(
                        str(args, 0),
                        integer(args, 1),
                        nonNegative(integer(args, 2), "connect timeout")));
            };
            case "listen" -> (BuiltinCallable) args -> {
                require(args, 4, name);
                admit(name);
                return io(() -> NativeSocketBridge.listenHandle(
                        str(args, 0),
                        integer(args, 1),
                        positive(integer(args, 2), "listen backlog"),
                        bool(args, 3)));
            };
            case "accept" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                return io(() -> NativeSocketBridge.accept(handle(args, 0, name)));
            };
            case "read" -> (BuiltinCallable) args -> {
                require(args, 4, name);
                admit(name);
                NativeSocketHandle handle = handle(args, 0, name);
                List<Object> target = byteList(args.get(1), name);
                int offset = integer(args, 2);
                int length = integer(args, 3);
                validateRange(target.size(), offset, length, name);
                byte[] bytes = toBytes(target);
                int count = io(() -> NativeSocketBridge.read(
                        handle, bytes, offset, length));
                if (count > 0) {
                    for (int i = offset; i < offset + count; i++) {
                        target.set(i, (long) (bytes[i] & 0xff));
                    }
                }
                return (long) count;
            };
            case "read_some" -> (BuiltinCallable) args -> {
                require(args, 2, name);
                admit(name);
                NativeSocketHandle handle = handle(args, 0, name);
                int maximum = positive(integer(args, 1), "read_some maximum");
                if (maximum > MAX_READ_SOME_BYTES) {
                    throw new IllegalArgumentException(
                            "native_net.read_some maximum exceeds " + MAX_READ_SOME_BYTES + " bytes");
                }
                byte[] bytes = new byte[maximum];
                int count = io(() -> NativeSocketBridge.read(handle, bytes, 0, bytes.length));
                if (count < 0) return List.of();
                ArrayList<Object> result = new ArrayList<>(count);
                for (int i = 0; i < count; i++) result.add((long) (bytes[i] & 0xff));
                return result;
            };
            case "write" -> (BuiltinCallable) args -> {
                require(args, 4, name);
                admit(name);
                NativeSocketHandle handle = handle(args, 0, name);
                List<Object> source = byteList(args.get(1), name);
                int offset = integer(args, 2);
                int length = integer(args, 3);
                validateRange(source.size(), offset, length, name);
                byte[] bytes = toBytes(source);
                return (long) io(() -> NativeSocketBridge.write(
                        handle, bytes, offset, length));
            };
            case "shutdown_input" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                ioVoid(() -> NativeSocketBridge.shutdownInput(handle(args, 0, name)));
                return OresNull.INSTANCE;
            };
            case "shutdown_output" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                ioVoid(() -> NativeSocketBridge.shutdownOutput(handle(args, 0, name)));
                return OresNull.INSTANCE;
            };
            case "close" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                ioVoid(() -> NativeSocketBridge.close(handle(args, 0, name)));
                return OresNull.INSTANCE;
            };
            case "is_open" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                return handle(args, 0, name).isOpen();
            };
            case "remote_address" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                return io(() -> NativeSocketBridge.remoteAddress(handle(args, 0, name)));
            };
            case "local_address" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                return io(() -> NativeSocketBridge.localAddress(handle(args, 0, name)));
            };
            case "remote_port" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                return (long) io(() -> NativeSocketBridge.remotePort(handle(args, 0, name)));
            };
            case "local_port" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                return (long) io(() -> NativeSocketBridge.localPort(handle(args, 0, name)));
            };
            case "resolve_all" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                admit(name);
                String[] values = io(() -> NativeSocketBridge.resolveAll(str(args, 0)));
                ArrayList<Object> result = new ArrayList<>(values.length);
                for (String value : values) result.add(value);
                return result;
            };
            default -> throw new IllegalArgumentException(
                    "unknown native_net primitive " + name);
        };
    }

    private void admit(String operation) {
        NetworkAdmission.requireRawNetwork(context, "native_net." + operation);
    }

    private static void require(List<Object> args, int count, String operation) {
        if (args.size() != count) {
            throw new IllegalArgumentException(
                    "native_net." + operation + " expects " + count + " arguments");
        }
    }

    private static NativeSocketHandle handle(
            List<Object> args,
            int index,
            String operation) {
        Object value = args.get(index);
        if (value instanceof NativeSocketHandle handle) return handle;
        throw new IllegalArgumentException(
                "native_net." + operation
                        + " expects an opaque NativeSocketHandle at argument " + (index + 1));
    }

    private static String str(List<Object> args, int index) {
        if (args.get(index) instanceof String value) return value;
        throw new IllegalArgumentException(
                "native_net expects String argument " + (index + 1));
    }

    private static int integer(List<Object> args, int index) {
        if (!(args.get(index) instanceof Number value)) {
            throw new IllegalArgumentException(
                    "native_net expects integer argument " + (index + 1));
        }
        long result = value.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer out of range");
        }
        return (int) result;
    }

    private static boolean bool(List<Object> args, int index) {
        if (args.get(index) instanceof Boolean value) return value;
        throw new IllegalArgumentException(
                "native_net expects Bool argument " + (index + 1));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> byteList(Object value, String operation) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new IllegalArgumentException(
                "native_net." + operation + " expects a byte array");
    }

    private static void validateRange(
            int size,
            int offset,
            int length,
            String operation) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new IllegalArgumentException(
                    "native_net." + operation + " byte range is out of bounds");
        }
    }

    private static byte[] toBytes(List<Object> values) {
        byte[] result = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object item = values.get(i);
            if (!(item instanceof Number number)
                    || number.longValue() < 0
                    || number.longValue() > 255) {
                throw new IllegalArgumentException(
                        "native_net byte arrays contain integers 0..255");
            }
            result[i] = (byte) number.intValue();
        }
        return result;
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static <T> T io(IOSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private static void ioVoid(IORunnable runnable) {
        try {
            runnable.run();
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    @FunctionalInterface
    private interface IOSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    private interface IORunnable {
        void run() throws IOException;
    }
}
