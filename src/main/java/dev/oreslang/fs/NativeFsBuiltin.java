package dev.oreslang.fs;

import dev.oreslang.runtime.BuiltinCallable;
import dev.oreslang.runtime.BuiltinValue;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Capability-gated primitive ABI used by the Oreslang filesystem stdlib.
 *
 * <p>Filesystem policy and higher-level APIs belong in .ores. This class only
 * admits capabilities and marshals guest values into liboresfs calls.</p>
 */
public final class NativeFsBuiltin implements BuiltinValue {
    private static final int MAX_READ_SOME_BYTES = 1024 * 1024;

    private final OresContext context;

    public NativeFsBuiltin(OresContext context) {
        this.context = context;
    }

    @Override
    public Object member(String name) {
        return switch (name) {
            case "open_read" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                readCapability(name);
                return io(() -> NativeFileBridge.openReadHandle(str(args, 0)));
            };
            case "open_write_truncate" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                writeCapability(name);
                return io(() -> NativeFileBridge.openWriteTruncateHandle(str(args, 0)));
            };
            case "open_write_append" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                writeCapability(name);
                return io(() -> NativeFileBridge.openWriteAppendHandle(str(args, 0)));
            };
            case "read" -> (BuiltinCallable) args -> {
                require(args, 4, name);
                readCapability(name);
                NativeFileHandle handle = handle(args, 0, name);
                List<Object> target = byteList(args.get(1), name);
                int offset = integer(args, 2);
                int length = integer(args, 3);
                validateRange(target.size(), offset, length, name);
                byte[] bytes = toBytes(target);
                int count = io(() -> NativeFileBridge.read(
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
                readCapability(name);
                NativeFileHandle handle = handle(args, 0, name);
                int maximum = positive(integer(args, 1), "read_some maximum");
                if (maximum > MAX_READ_SOME_BYTES) {
                    throw new IllegalArgumentException(
                            "native_fs.read_some maximum exceeds "
                                    + MAX_READ_SOME_BYTES + " bytes");
                }
                byte[] bytes = new byte[maximum];
                int count = io(() -> NativeFileBridge.read(handle, bytes, 0, bytes.length));
                if (count < 0) return List.of();
                ArrayList<Object> result = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    result.add((long) (bytes[i] & 0xff));
                }
                return result;
            };
            case "write" -> (BuiltinCallable) args -> {
                require(args, 4, name);
                writeCapability(name);
                NativeFileHandle handle = handle(args, 0, name);
                List<Object> source = byteList(args.get(1), name);
                int offset = integer(args, 2);
                int length = integer(args, 3);
                validateRange(source.size(), offset, length, name);
                byte[] bytes = toBytes(source);
                return (long) io(() -> NativeFileBridge.write(
                        handle, bytes, offset, length));
            };
            case "size" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                readCapability(name);
                return io(() -> NativeFileBridge.size(handle(args, 0, name)));
            };
            case "fsync" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                writeCapability(name);
                ioVoid(() -> NativeFileBridge.fsync(handle(args, 0, name)));
                return OresNull.INSTANCE;
            };
            case "is_open" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                return handle(args, 0, name).isOpen();
            };
            case "close" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                ioVoid(() -> NativeFileBridge.close(handle(args, 0, name)));
                return OresNull.INSTANCE;
            };
            case "remove_file" -> (BuiltinCallable) args -> {
                require(args, 1, name);
                writeCapability(name);
                ioVoid(() -> NativeFileBridge.removeFile(str(args, 0)));
                return OresNull.INSTANCE;
            };
            default -> throw new IllegalArgumentException(
                    "unknown native_fs primitive " + name);
        };
    }

    private void readCapability(String operation) {
        context.requireCapability(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                "native_fs." + operation);
    }

    private void writeCapability(String operation) {
        context.requireCapability(
                IsolatePolicy.Capability.FILESYSTEM_WRITE,
                "native_fs." + operation);
    }

    private static void require(List<Object> args, int count, String operation) {
        if (args.size() != count) {
            throw new IllegalArgumentException(
                    "native_fs." + operation + " expects " + count + " arguments");
        }
    }

    private static NativeFileHandle handle(
            List<Object> args,
            int index,
            String operation) {
        Object value = args.get(index);
        if (value instanceof NativeFileHandle handle) return handle;
        throw new IllegalArgumentException(
                "native_fs." + operation
                        + " expects an opaque NativeFileHandle at argument " + (index + 1));
    }

    private static String str(List<Object> args, int index) {
        if (args.get(index) instanceof String value) return value;
        throw new IllegalArgumentException(
                "native_fs expects string argument " + (index + 1));
    }

    private static int integer(List<Object> args, int index) {
        if (!(args.get(index) instanceof Number value)) {
            throw new IllegalArgumentException(
                    "native_fs expects integer argument " + (index + 1));
        }
        long result = value.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer out of range");
        }
        return (int) result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> byteList(Object value, String operation) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new IllegalArgumentException(
                "native_fs." + operation + " expects a byte array");
    }

    private static void validateRange(
            int size,
            int offset,
            int length,
            String operation) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new IllegalArgumentException(
                    "native_fs." + operation + " byte range is out of bounds");
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
                        "native_fs byte arrays contain integers 0..255");
            }
            result[i] = (byte) number.intValue();
        }
        return result;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
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
