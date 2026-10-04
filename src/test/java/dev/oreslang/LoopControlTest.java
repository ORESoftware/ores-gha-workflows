package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class LoopControlTest {
    @Test
    void continueInClassicForStillRunsUpdateExpression() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let i = 0;
                  let total = 0;
                  for (; i < 5; i = i + 1) {
                    if i == 2; do
                      continue;
                    fi
                    total = total + i;
                  }
                  stdio.stdout.write(total);
                }
                """);

        assertEquals("8", output);
    }

    @Test
    void breakSkipsClassicForUpdateAndExitsNearestLoop() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let outer = 0;
                  let count = 0;
                  for (; outer < 3; outer = outer + 1) {
                    let inner = 0;
                    for (; inner < 5; inner = inner + 1) {
                      if inner == 2; do
                        break;
                      fi
                      count = count + 1;
                    }
                  }
                  stdio.stdout.write(count);
                }
                """);

        assertEquals("6", output);
    }

    @Test
    void forOfSupportsContinueAndBreak() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let total = 0;
                  for (val item of arr[1, 2, 3, 4, 5]) {
                    if item == 2; do
                      continue;
                    fi
                    if item == 5; do
                      break;
                    fi
                    total = total + item;
                  }
                  stdio.stdout.write(total);
                }
                """);

        assertEquals("8", output);
    }

    @Test
    void finallyRunsBeforeContinueAndClassicUpdateStillRuns() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let i = 0;
                  for (; i < 2; i = i + 1) {
                    try {
                      stdio.stdout.write("T");
                      continue;
                    } catch (err) {
                      stdio.stdout.write("C");
                    } finally {
                      stdio.stdout.write("F");
                    }
                  }
                  stdio.stdout.write(i);
                }
                """);

        assertEquals("TFTF2", output);
    }

    @Test
    void finallyRunsBeforeBreakWithoutRunningClassicUpdate() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let i = 0;
                  for (; i < 5; i = i + 1) {
                    try {
                      stdio.stdout.write("T");
                      break;
                    } catch (err) {
                      stdio.stdout.write("C");
                    } finally {
                      stdio.stdout.write("F");
                    }
                  }
                  stdio.stdout.write(i);
                }
                """);

        assertEquals("TF0", output);
    }

    @Test
    void breakDoesNotDrainCallableScopedDefers() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (let i = 0; i < 1; i = i + 1) {
                    defer || -> {
                      stdio.stdout.write("D");
                    };
                    break;
                  }
                  stdio.stdout.write("A");
                }
                """);

        assertEquals("AD", output);
    }

    @Test
    void breakOutsideLoopIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          break;
                        }
                        """)));

        assertTrue(error.getMessage().contains("'break' is only valid inside a loop"));
    }

    @Test
    void continueOutsideLoopIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          continue;
                        }
                        """)));

        assertTrue(error.getMessage().contains("'continue' is only valid inside a loop"));
    }

    @Test
    void lambdaCannotBreakItsEnclosingCreatorsLoop() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          for (let i = 0; i < 2; i = i + 1) {
                            val invalid = || -> {
                              break;
                            };
                          }
                        }
                        """)));

        assertTrue(error.getMessage().contains("'break' is only valid inside a loop"));
    }

    @Test
    void lambdaMayUseBreakInsideItsOwnLoop() {
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          val valid = || -> {
                            for (let i = 0; i < 2; i = i + 1) {
                              break;
                            }
                          };
                        }
                        """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "loop-control.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
