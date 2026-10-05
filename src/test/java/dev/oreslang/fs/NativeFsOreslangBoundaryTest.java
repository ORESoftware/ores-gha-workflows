package dev.oreslang.fs;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeFsOreslangBoundaryTest {

    @Test
    void nativeFsStdlibActuallyParsesAndTypeChecks() throws Exception {
        String source = Files.readString(Path.of("stdlib/fs/native.ores"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void numericFdCannotSatisfyNativeFileHandleAbi() {
        Exception error = assertThrows(
                Exception.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad() => void {
                          val bytes = arr[0, 0, 0, 0];
                          native_fs.read(7, &mut bytes, 0, 4);
                          return;
                        }
                        """)));
        assertTrue(
                error.toString().contains("NativeFileHandle")
                        || error.toString().contains("Borrow"),
                error::toString);
    }

    @Test
    void fileCapabilityCannotCrossActorBoundaryByValue() {
        Exception error = assertThrows(
                Exception.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc leak(NativeFileHandle handle) => void {
                          return;
                        }
                        """)));
        assertTrue(error.toString().contains("NativeFileHandle"), error::toString);
        assertTrue(error.toString().contains("actor boundary"), error::toString);
    }

    @Test
    void stdlibNeverModelsFileIdentityAsIntegerFd() throws Exception {
        String source = Files.readString(Path.of("stdlib/fs/native.ores"));
        assertFalse(source.contains("int fd"));
        assertFalse(source.contains("int handle"));
        assertTrue(source.contains("NativeFileHandle"));
        assertTrue(source.contains("&NativeFileHandle"));
    }
}
