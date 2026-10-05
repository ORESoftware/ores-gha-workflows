package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host bootstrap for JNI libraries.
 *
 * <p>This is intentionally the only Java/NIO path lookup used by native
 * runtime bridges. Guest file/network semantics must live in Oreslang or the
 * native OS layer, not in this loader.</p>
 */
public final class NativeLibraryLoader {
    private NativeLibraryLoader() { }

    public static void load(String library, String explicitProperty) {
        String explicit = System.getProperty(explicitProperty);
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        Path local = Path.of("target", "native", System.mapLibraryName(library))
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }

        System.loadLibrary(library);
    }
}
