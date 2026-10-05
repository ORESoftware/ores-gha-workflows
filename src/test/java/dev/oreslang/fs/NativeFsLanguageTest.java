package dev.oreslang.fs;

import dev.oreslang.OresLanguage;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeFsLanguageTest {

    @Test
    void oreslangWritesReadsAndRemovesFileThroughNativeFs() throws Exception {
        Path directory = Files.createTempDirectory("oreslang-native-fs-language-");
        Path path = directory.resolve("roundtrip.bin");
        String escapedPath = path.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        String stdlib = Files.readString(Path.of("stdlib/fs/native.ores"));
        String program = stdlib + """

                pub routine main() => void {
                  val output = native_fs_api.open_write_truncate("%s");
                  val payload = arr[79, 114, 101, 115];
                  val written = native_fs_api.write_all(&output, &payload, 4);
                  stdio.println(written);
                  native_fs_api.fsync(&output);
                  native_fs_api.close(output);

                  val input = native_fs_api.open_read("%s");
                  stdio.println(native_fs_api.size(&input));
                  val bytes = native_fs_api.read_some(&input, 16);
                  stdio.println(bytes.length);
                  stdio.println(bytes[0]);
                  stdio.println(bytes[1]);
                  stdio.println(bytes[2]);
                  stdio.println(bytes[3]);
                  native_fs_api.close(input);
                  native_fs_api.remove_file("%s");
                  return;
                }
                """.formatted(escapedPath, escapedPath, escapedPath);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);

        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "native-fs-language-test.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = policy.restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("4"));
        assertTrue(rendered.contains("79"));
        assertTrue(rendered.contains("114"));
        assertTrue(rendered.contains("101"));
        assertTrue(rendered.contains("115"));
        assertFalse(Files.exists(path));
    }

    @Test
    void nativeFsRequiresExplicitCapabilities() throws Exception {
        String program = """
                pub routine main() => void {
                  native_fs.open_read("/tmp/does-not-matter");
                  return;
                }
                """;

        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "native-fs-denied-test.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        IsolatePolicy policy = IsolatePolicy.developer()
                .withoutCapabilities(
                        IsolatePolicy.Capability.FILESYSTEM_READ,
                        IsolatePolicy.Capability.FILESYSTEM_WRITE);

        try (Context context = policy.restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
            Exception error = assertThrows(Exception.class, () -> context.eval(source));
            assertTrue(error.toString().contains("FILESYSTEM_READ"), error::toString);
        }
    }
}
