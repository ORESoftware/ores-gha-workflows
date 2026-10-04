package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class DeferSemanticsTest {
    @Test
    void deferredLambdasRunLifoAtCallableExit() throws Exception {
        String output = run("""
                pub routine main() => void {
                  defer || -> {
                    stdio.stdout.write("A");
                  };
                  defer || -> {
                    stdio.stdout.write("B");
                  };
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("MBA", output);
    }

    @Test
    void deferRunsOnExplicitReturn() throws Exception {
        String output = run("""
                fnc inner() => int {
                  defer || -> {
                    stdio.stdout.write("D");
                  };
                  stdio.stdout.write("R");
                  return 7;
                }

                pub routine main() => void {
                  stdio.stdout.write(inner());
                  return;
                }
                """);

        assertEquals("RD7", output);
    }

    @Test
    void nestedBlockDefersBelongToTheEnclosingCallable() throws Exception {
        String output = run("""
                pub routine main() => void {
                  if true; do
                    defer || -> {
                      stdio.stdout.write("D");
                    };
                    stdio.stdout.write("I");
                  fi
                  stdio.stdout.write("O");
                }
                """);

        assertEquals("IOD", output);
    }

    @Test
    void callableVariablesCanBeDeferred() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val cleanup = || -> {
                    stdio.stdout.write("D");
                  };
                  defer cleanup;
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("MD", output);
    }

    @Test
    void namedMoveOnlyCallableIsOwnedByTheDeferFrame() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          val cleanup = || -> {
                            stdio.stdout.write("D");
                          };
                          defer cleanup;
                          cleanup();
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void producerCallRunsNowAndReturnedFunctionRunsLater() throws Exception {
        String output = run("""
                fnc make_cleanup() => typeof fnc() -> void {
                  stdio.stdout.write("P");
                  return || -> {
                    stdio.stdout.write("D");
                  };
                }

                pub routine main() => void {
                  defer make_cleanup();
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("PMD", output);
    }

    @Test
    void methodProducerMakesDeferCloseSyntaxTruthful() throws Exception {
        String output = run("""
                define module io
                  define class File
                    Close() => typeof fnc() -> void {
                      stdio.stdout.write("P");
                      return || -> {
                        stdio.stdout.write("D");
                      };
                    }
                  end
                end

                pub routine main() => void {
                  val f = new io.File();
                  defer f.Close();
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("PMD", output);
    }

    @Test
    void selfInvokingProducerMustReturnTheDeferredFunction() throws Exception {
        String output = run("""
                pub routine main() => void {
                  defer (|| -> {
                    stdio.stdout.write("P");
                    return || -> {
                      stdio.stdout.write("D");
                    };
                  })();
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("PMD", output);
    }

    @Test
    void selfInvokingVoidLambdaIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          defer (|| -> {
                            stdio.stdout.write("X");
                          })();
                        }
                        """)));

        assertTrue(error.getMessage().contains("arity-0 function"));
    }

    @Test
    void ordinaryVoidCallIsRejectedInsteadOfBeingMagicallyWrapped() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc cleanup() => void {
                          return;
                        }

                        pub routine main() => void {
                          defer cleanup();
                        }
                        """)));

        assertTrue(error.getMessage().contains("arity-0 function"));
    }

    @Test
    void nonZeroArityCallableIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          defer |int x| -> {
                            stdio.stdout.write(x);
                          };
                        }
                        """)));

        assertTrue(error.getMessage().contains("arity-0 function"));
    }

    @Test
    void loopDefersAccumulateOnOneCallableFrameAndCaptureEachIteration() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (val item of arr[1, 2, 3]) {
                    defer || -> {
                      stdio.stdout.write(item);
                    };
                  }
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("M321", output);
    }

    @Test
    void deferStackContinuesDrainingAfterADeferredFailure() throws Exception {
        String output = run("""
                fnc inner() => void {
                  defer || -> {
                    stdio.stdout.write("G");
                  };
                  defer || -> {
                    val values = arr[1];
                    val nope = values[9];
                  };
                }

                pub routine main() => void {
                  try {
                    inner();
                  } catch (err) {
                    stdio.stdout.write("C");
                  }
                }
                """);

        assertEquals("GC", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "defer.ores")
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
