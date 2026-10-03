package dev.oreslang.net;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeNetOreslangBoundaryTest {

    @Test
    void nativeNetStdlibActuallyParsesAndTypeChecks() throws Exception {
        String source = Files.readString(Path.of("stdlib/net/native.ores"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void httpPolicyStdlibActuallyParsesAndTypeChecks() throws Exception {
        String source = Files.readString(Path.of("stdlib/net/http.ores"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertTrue(source.contains("redirect_rewrites_to_get"));
        assertTrue(source.contains("response_may_have_body"));
        assertTrue(source.contains("restricted_request_header"));
    }

    @Test
    void numericFdCannotSatisfyNativeSocketHandleAbi() {
        Exception error = assertThrows(
                Exception.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad() => void {
                          val bytes = arr[0, 0, 0, 0];
                          native_net.read(7, bytes, 0, 4);
                          return;
                        }
                        """)));
        assertTrue(error.toString().contains("NativeSocketHandle")
                        || error.toString().contains("Borrow"),
                error::toString);
    }

    @Test
    void opaqueHandleCanOnlyBeBorrowedForIoAndConsumedByClose() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc good() => void {
                  val listener = native_net.listen("127.0.0.1", 18080, 16, true);
                  val client = native_net.accept(&listener);
                  val bytes = native_net.read_some(&client, 1024);
                  val reply = arr[79, 75];
                  native_net.write(&client, &reply, 0, 2);
                  native_net.close(client);
                  native_net.close(listener);
                  return;
                }
                """)));
    }

    @Test
    void socketCapabilityCannotCrossActorBoundaryByValue() {
        Exception error = assertThrows(
                Exception.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc leak(NativeSocketHandle handle) => void {
                          return;
                        }
                        """)));
        assertTrue(error.toString().contains("NativeSocketHandle"), error::toString);
        assertTrue(error.toString().contains("actor boundary"), error::toString);
    }

    @Test
    void stdlibNeverModelsSocketIdentityAsAnIntegerFd() throws Exception {
        String source = Files.readString(Path.of("stdlib/net/native.ores"));
        assertFalse(source.contains("int fd"));
        assertFalse(source.contains("int handle"));
        assertTrue(source.contains("NativeSocketHandle"));
        assertTrue(source.contains("&NativeSocketHandle"));
        assertTrue(source.contains("write_all"));
        assertTrue(source.contains("write_all_from"));

        String bridge = Files.readString(
                Path.of("src/main/java/dev/oreslang/net/NativeNetBuiltin.java"));
        assertFalse(bridge.contains("write_utf8"));
        assertFalse(bridge.contains("StandardCharsets"));
    }
}
