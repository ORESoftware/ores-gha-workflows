package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRecoverTest {
    @Test
    void recoverHandlerDoesNotRunOnSuccessfulReturn() throws Exception {
        String output = run("""
                fnc inner() => int {
                  recover |err| -> {
                    stdio.stdout.write("R");
                    return 0;
                  };

                  stdio.stdout.write("S");
                  return 7;
                }

                pub routine main() => void {
                  stdio.stdout.write(inner());
                }
                """);

        assertEquals("S7", output);
    }

    @Test
    void recoverFallbackMustMatchEnclosingCallableResult() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc inner() => int {
                          recover |err| -> {
                            return "wrong";
                          };

                          panic "boom";
                        }
                        """)));

        assertTrue(error.getMessage().contains("recover handler result"));
    }

    @Test
    void recoverConsumesPanicBeforeCallerTryCatch() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write("R");
                    return;
                  };

                  panic "boom";
                }

                pub routine main() => void {
                  try {
                    inner();
                    stdio.stdout.write("M");
                  } catch (err) {
                    stdio.stdout.write("C");
                  }
                }
                """);

        assertEquals("RM", output);
    }

    @Test
    void recoverMayObserveThenRepanicSameFailureValue() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write(err);
                    panic err;
                  };

                  panic "boom";
                }

                pub routine main() => void {
                  try {
                    inner();
                  } catch (err) {
                    stdio.stdout.write(":");
                    stdio.stdout.write(err);
                  }
                }
                """);

        assertEquals("boom:boom", output);
    }

    @Test
    void recoverMayRepanicToCallerCatch() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write("R");
                    panic err;
                  };

                  panic "boom";
                }

                pub routine main() => void {
                  try {
                    inner();
                  } catch (err) {
                    stdio.stdout.write(err);
                  }
                }
                """);

        assertEquals("Rboom", output);
    }

    @Test
    void recoverValueMayBeObservedAndThenRepanicked() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write(err);
                    panic err;
                  };

                  panic "boom";
                }

                pub routine main() => void {
                  try {
                    inner();
                  } catch (err) {
                    stdio.stdout.write(err);
                  }
                }
                """);

        assertEquals("boomboom", output);
    }

    @Test
    void recoverRegisteredInNestedBlockRemainsCallableScoped() throws Exception {
        String output = run("""
                fnc inner() => void {
                  if true; do
                    recover |err| -> {
                      stdio.stdout.write("R");
                      return;
                    };
                  fi

                  panic "boom";
                }

                pub routine main() => void {
                  inner();
                }
                """);

        assertEquals("R", output);
    }

    @Test
    void defersRunBeforeRecover() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write("R");
                    return;
                  };

                  defer || -> {
                    stdio.stdout.write("D");
                  };

                  panic "boom";
                }

                pub routine main() => void {
                  inner();
                }
                """);

        assertEquals("DR", output);
    }

    @Test
    void recoverHandlesOrdinaryRuntimeExceptions() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write("R");
                    return;
                  };

                  val values = arr[1];
                  val nope = values[9];
                }

                pub routine main() => void {
                  inner();
                }
                """);

        assertEquals("R", output);
    }

    @Test
    void inferredLambdaIncludesRecoverFallbackInItsResultType() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val compute = || -> {
                    recover |err| -> {
                      return 7;
                    };

                    panic "boom";
                  };

                  stdio.stdout.write(compute());
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void recoverMayProvideFallbackValueForNonVoidCallable() throws Exception {
        String output = run("""
                fnc compute() => int {
                  recover |err| -> {
                    return 7;
                  };

                  panic "boom";
                }

                pub routine main() => void {
                  stdio.stdout.write(compute());
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void sameCallableTryCatchHandlesBeforeCallableRecover() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write("R");
                    return;
                  };

                  try {
                    panic "boom";
                  } catch (err) {
                    stdio.stdout.write("C");
                  }
                }

                pub routine main() => void {
                  inner();
                }
                """);

        assertEquals("C", output);
    }

    @Test
    void multipleRecoverHandlersUnwindLifoAndCanRepanic() throws Exception {
        String output = run("""
                fnc inner() => void {
                  recover |err| -> {
                    stdio.stdout.write("O");
                    return;
                  };

                  recover |err| -> {
                    stdio.stdout.write("I");
                    panic "again";
                  };

                  panic "first";
                }

                pub routine main() => void {
                  inner();
                }
                """);

        assertEquals("IO", output);
    }

    @Test
    void actorSelfCanSendAndStopWithoutOuterHandleCapture() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val worker = actor |int value| -> {
                    stdio.stdout.write(value);

                    if value < 2; do
                      self.send(value + 1);
                    else
                      self.stop();
                    fi
                  };

                  worker.send(0);
                  worker.join();
                }
                """);

        assertEquals("012", output);
    }

    @Test
    void actorSelfJoinFailsRecoverablyInsteadOfDeadlocking() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val worker = actor |String msg| -> {
                    recover |err| -> {
                      stdio.stdout.write("R");
                      self.stop();
                      return;
                    };

                    self.join();
                  };

                  worker.send("go");
                  worker.join();
                  stdio.stdout.write(worker.failed);
                }
                """);

        assertEquals("Rfalse", output);
    }

    @Test
    void actorFunctionDeclarationSpawnsOneShotTask() throws Exception {
        String output = run("""
                pub actor fnc rebuild(String target) => void {
                  stdio.stdout.write(target);
                }

                pub routine main() => void {
                  val task = rebuild("X");
                  task.join();
                  stdio.stdout.write(task.failed);
                  stdio.stdout.write(task.kind);
                }
                """);

        assertEquals("Xfalseshared", output);
    }

    @Test
    void actorIsolateRoutineDeclarationSpawnsIsolateTask() throws Exception {
        String output = run("""
                pub actor isolate routine compact(String target) => void {
                  stdio.stdout.write(target);
                }

                pub routine main() => void {
                  val task = compact("I");
                  task.join();
                  stdio.stdout.write(task.kind);
                }
                """);

        assertEquals("Iisolate", output);
    }

    @Test
    void actorTaskFailureDoesNotKillCaller() throws Exception {
        String output = run("""
                actor fnc explode(String message) => void {
                  panic message;
                }

                pub routine main() => void {
                  val task = explode("boom");
                  task.join();
                  stdio.stdout.write(task.failed);
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("trueM", output);
    }

    @Test
    void actorTaskHandleCanCrossAnotherActorMailbox() throws Exception {
        String output = run("""
                actor fnc work() => void {
                  stdio.stdout.write("W");
                }

                pub routine main() => void {
                  val task = work();

                  val observer = actor |ActorTask observed| -> {
                    observed.join();
                    stdio.stdout.write(observed.failed);
                  };

                  observer.send(task);
                  observer.stop();
                  observer.join();
                }
                """);

        assertEquals("Wfalse", output);
    }

    @Test
    void actorModifierIsRejectedOnNonCallableDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor val value = 1;
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor interface Bad {
                  fnc x() => void;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor define class Bad
                end
                """));
    }

    @Test
    void actorCallableMustDeclareVoid() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        actor fnc bad() => int {
                          return 1;
                        }
                        """)));

        assertTrue(error.getMessage().contains("must declare void"));
    }

    @Test
    void mainCannotBeDeclaredAsActor() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub actor routine main() => void {
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("main is a file/module lifecycle entrypoint"));
        assertTrue(error.getMessage().contains("cannot be declared actor"));
    }

    @Test
    void actorCallableCannotAlsoBeAsync() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        async actor fnc bad() => void {
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot also be async"));
    }

    @Test
    void actorTaskDoesNotExposeMailboxSend() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        actor fnc work() => void {
                          return;
                        }

                        pub routine main() => void {
                          val task = work();
                          task.send("nope");
                        }
                        """)));

        assertTrue(error.getMessage().contains("unknown ActorTask member"));
    }

    @Test
    void recoveredActorContinuesAndStopsWithoutFailure() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val worker = actor |String msg| -> {
                    recover |err| -> {
                      stdio.stdout.write("R");
                      return;
                    };

                    if msg == "boom"; do
                      panic msg;
                    fi

                    stdio.stdout.write("O");
                  };

                  worker.send("boom");
                  worker.send("ok");
                  worker.stop();
                  worker.join();

                  stdio.stdout.write(worker.failed);
                  stdio.stdout.write(worker.alive);
                  stdio.stdout.write(worker.kind);
                }
                """);

        assertEquals("ROfalsefalseshared", output);
    }

    @Test
    void unrecoveredActorDiesWithoutKillingMainOrSibling() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val bad = actor |String msg| -> {
                    panic msg;
                  };

                  val good = actor |String msg| -> {
                    stdio.stdout.write("G");
                  };

                  bad.send("boom");
                  bad.join();

                  good.send("ok");
                  good.stop();
                  good.join();

                  stdio.stdout.write(bad.failed);
                  stdio.stdout.write(bad.alive);
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("GtruefalseM", output);
    }

    @Test
    void sharedActorMayOwnMutableCapturedState() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let total = 0;

                  val worker = actor |int value| -> {
                    total = total + value;
                    stdio.stdout.write(total);
                  };

                  worker.send(1);
                  worker.send(2);
                  worker.stop();
                  worker.join();
                }
                """);

        assertEquals("13", output);
    }

    @Test
    void isolateActorMayDieWithoutKillingMain() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val worker = actor isolate |String msg| -> {
                    panic msg;
                  };

                  worker.send("boom");
                  worker.join();

                  stdio.stdout.write(worker.failed);
                  stdio.stdout.write("M");
                }
                """);

        assertEquals("trueM", output);
    }

    @Test
    void isolateActorAllowsCopyOnlyCaptureAndReportsKind() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val prefix = "P";

                  val worker = actor isolate |String msg| -> {
                    stdio.stdout.write(prefix);
                    stdio.stdout.write(msg);
                  };

                  worker.send("X");
                  worker.stop();
                  worker.join();
                  stdio.stdout.write(worker.kind);
                }
                """);

        assertEquals("PXisolate", output);
    }

    @Test
    void isolateActorRejectsMutableOuterCapture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          let total = 0;

                          val worker = actor isolate |int value| -> {
                            total = total + value;
                          };
                        }
                        """)));

        assertTrue(error.getMessage().contains("isolate actor cannot capture"));
    }

    @Test
    void actorBehaviorMustHaveExactlyOneMailboxParameter() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          val worker = actor || -> {
                            return;
                          };
                        }
                        """)));

        assertTrue(error.getMessage().contains("exactly one mailbox message"));
    }

    @Test
    void recoverHandlerMustHaveArityOne() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          recover || -> {
                            return;
                          };
                        }
                        """)));

        assertTrue(error.getMessage().contains("arity-1"));
    }

    @Test
    void capabilityCheckerDescendsIntoActorBodies() {
        IsolatePolicy denyAll = denyAllPolicy();

        assertThrows(SecurityException.class, () ->
                OresCompiler.validateForIsolate("""
                        pub routine main() => void {
                          val worker = actor |String msg| -> {
                            stdio.println(msg);
                          };
                        }
                        """, denyAll));
    }

    @Test
    void capabilityCheckerDescendsIntoRecoverHandlers() {
        IsolatePolicy denyAll = denyAllPolicy();

        assertThrows(SecurityException.class, () ->
                OresCompiler.validateForIsolate("""
                        fnc inner() => void {
                          recover |err| -> {
                            stdio.println(err);
                            return;
                          };
                          panic "boom";
                        }

                        pub routine main() => void {
                          inner();
                        }
                        """, denyAll));
    }

    @Test
    void capabilityCheckerDescendsIntoPanicPayloads() {
        IsolatePolicy denyAll = denyAllPolicy();

        assertThrows(SecurityException.class, () ->
                OresCompiler.validateForIsolate("""
                        pub routine main() => void {
                          panic process.context_id;
                        }
                        """, denyAll));
    }

    @Test
    void panicCountsAsTerminatingPathForNonVoidCallable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc value_or_panic(bool ok) => int {
                  if ok; do
                    return 42;
                  else
                    panic "no value";
                  fi
                }
                """)));
    }

    private static IsolatePolicy denyAllPolicy() {
        return new IsolatePolicy(
                Set.of(),
                64L * 1024 * 1024,
                128,
                Duration.ofSeconds(5),
                false);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-recover.ores")
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
