package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class IsolationHotReloadTest {
    @Test
    void executionProfilesCoverJitAotAndHybrid() {
        assertTrue(ExecutionProfile.serverJit().guestJitAllowed());
        assertFalse(ExecutionProfile.serverJit().hostAheadOfTime());

        ExecutionProfile hybrid = ExecutionProfile.serverHybrid();
        assertTrue(hybrid.guestJitAllowed());
        assertTrue(hybrid.hostAheadOfTime());

        ExecutionProfile ios = ExecutionProfile.mobileAot(ExecutionProfile.Platform.IOS);
        assertTrue(ios.hostAheadOfTime());
        assertFalse(ios.guestJitAllowed());
        assertTrue(ios.supportsSourceHotReload());

        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionProfile(ExecutionProfile.Mode.JIT, ExecutionProfile.Platform.IOS));
    }

    @Test
    void capabilityAdmissionRejectsForbiddenApiBeforeGuestExecution() {
        var program = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  stdio.stdout.write(process.context_id);
                }
                """));

        SecurityException denied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));

        assertDoesNotThrow(() -> CapabilityChecker.check(program,
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.PROCESS_INFO)));
    }

    @Test
    void runtimeCapabilityCheckCannotBeBypassedByFacadeDispatch() throws Exception {
        IsolatePolicy noOutput = new IsolatePolicy(Set.of(), 64L * 1024 * 1024, 32, Duration.ofSeconds(5));
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub routine main() => void {
                  stdio.stdout.write("forbidden");
                }
                """, "denied.ores").mimeType(OresLanguage.MIME_TYPE).buildLiteral();

        RuntimeException error = assertThrows(RuntimeException.class, () -> {
            try (Context context = noOutput.restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
                context.eval(source);
            }
        });
        assertTrue(error.getMessage().contains("STDOUT"));
    }

    @Test
    void hotReloadCreatesDistinctVersionedContextsWithoutFfi() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.load("v1.ores", """
                    pub routine main() => void { return; }
                    """);
            var second = hot.load("v2.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertNotEquals(first.id(), second.id());
            assertNotEquals(first.sha256(), second.sha256());
            assertNotSame(first.source(), second.source());
            assertEquals(second.id(), hot.active().id());
            assertEquals(2, hot.liveGenerations());

            hot.retire(first.id());
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void hotLoadStagesWithoutRunningMainUntilExplicitStart() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("staged.ores", """
                    pub routine main() => void {
                      val values = arr[1];
                      val boom = values[99];
                      return;
                    }
                    """);
            assertFalse(generation.started());
            assertThrows(RuntimeException.class, generation::start);
            assertTrue(generation.closed());
        }
    }

    @Test
    void generationStartIsExactlyOnceUnderConcurrentCallers() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("start-once.ores", """
                    pub routine main() => void { return; }
                    """);

            AtomicInteger successes = new AtomicInteger();
            AtomicInteger alreadyStarted = new AtomicInteger();

            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
                try {
                    generation.start();
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    if (expected.getMessage().contains("already started")) alreadyStarted.incrementAndGet();
                    else throw expected;
                }
            });
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                try {
                    generation.start();
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    if (expected.getMessage().contains("already started")) alreadyStarted.incrementAndGet();
                    else throw expected;
                }
            });

            CompletableFuture.allOf(first, second).join();

            assertEquals(1, successes.get());
            assertEquals(1, alreadyStarted.get());
            assertTrue(generation.started());
            assertFalse(generation.closed());
        }
    }

    @Test
    void staleHotReloadGenerationCannotRollBackSingletonCode() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.load("managed-singleton-generation.ores", """
                    define singleton module managed_generation as
                      let int count = 0;
                      pub fnc next() => int {
                        count = count + 1;
                        return count;
                      }
                    end

                    pub routine main() => void {
                      val int n = await managed_generation.next();
                      return;
                    }
                    """);

            var second = hot.load("managed-singleton-generation.ores", """
                    define singleton module managed_generation as
                      let int count = 0;
                      pub fnc next() => int {
                        count = count + 10;
                        return count;
                      }
                    end

                    pub routine main() => void {
                      val int n = await managed_generation.next();
                      return;
                    }
                    """);

            assertDoesNotThrow(second::start);
            RuntimeException stale = assertThrows(RuntimeException.class, first::start);
            assertTrue(String.valueOf(stale.getMessage()).contains("stale singleton generation")
                    || (stale.getCause() != null
                    && String.valueOf(stale.getCause().getMessage()).contains("stale singleton generation")));
        }
    }

    @Test
    void failedGenerationRestoresPreviousActiveGeneration() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var stable = hot.load("rollback.ores", """
                    pub routine main() => void { return; }
                    """);
            assertDoesNotThrow(stable::start);

            var broken = hot.load("rollback.ores", """
                    pub routine main() => void {
                      val values = arr[1];
                      val boom = values[99];
                      return;
                    }
                    """);
            assertEquals(broken.id(), hot.active("rollback.ores").id());

            assertThrows(RuntimeException.class, broken::start);
            assertTrue(broken.closed());
            assertEquals(stable.id(), hot.active("rollback.ores").id());
            assertEquals(stable.id(), hot.active().id());
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void closingGenerationDeregistersItFromActiveState() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("close-me.ores", """
                    pub routine main() => void { return; }
                    """);
            assertEquals(1, hot.liveGenerations());

            generation.close();

            assertTrue(generation.closed());
            assertNull(hot.active("close-me.ores"));
            assertNull(hot.active());
            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void closedHotReloadManagerCannotBeResurrected() {
        IsolatePolicy policy = IsolatePolicy.developer();
        HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit());

        var generation = hot.load("close-manager.ores", """
                pub routine main() => void { return; }
                """);
        hot.close();

        assertTrue(generation.closed());
        assertEquals(0, hot.liveGenerations());
        assertNull(hot.active());
        assertTrue(hot.activeGenerations().isEmpty());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> hot.load("after-close.ores", """
                        pub routine main() => void { return; }
                        """));
        assertTrue(error.getMessage().contains("closed"));

        assertDoesNotThrow(hot::close);
    }

    @Test
    void retiredGenerationCannotBeStartedAgain() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("retired.ores", """
                    pub routine main() => void { return; }
                    """);

            hot.retire(generation.id());

            assertTrue(generation.closed());
            assertThrows(IllegalStateException.class, generation::start);
            assertNull(hot.active("retired.ores"));
        }
    }

    @Test
    void retiringLatestGenerationFallsBackPerCodeUnitAndGlobally() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var a1 = hot.load("unit-a.ores", """
                    pub routine main() => void { return; }
                    """);
            var b1 = hot.load("unit-b.ores", """
                    pub routine main() => void { return; }
                    """);
            var a2 = hot.load("unit-a.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertEquals(a2.id(), hot.active("unit-a.ores").id());
            assertEquals(b1.id(), hot.active("unit-b.ores").id());
            assertEquals(a2.id(), hot.active().id());

            a2.close();

            assertEquals(a1.id(), hot.active("unit-a.ores").id());
            assertEquals(b1.id(), hot.active().id());

            b1.close();

            assertNull(hot.active("unit-b.ores"));
            assertEquals(a1.id(), hot.active().id());
        }
    }

    @Test
    void rejectedLoadDoesNotPoisonManagerAndLaterLoadStillWorks() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            assertThrows(RuntimeException.class, () -> hot.load("invalid.ores", """
                    pub routine main( => void {
                    }
                    """));

            assertEquals(0, hot.liveGenerations());
            assertNull(hot.active());

            var recovered = hot.load("valid.ores", """
                    pub routine main() => void { return; }
                    """);
            assertEquals(1, hot.liveGenerations());
            assertEquals(recovered.id(), hot.active().id());
            assertDoesNotThrow(recovered::start);
        }
    }

    @Test
    void reservedOresPolicyArgumentsCannotBeOverriddenByExtraMetadata() {
        IsolatePolicy policy = IsolatePolicy.developer();

        IllegalArgumentException capabilities = assertThrows(IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        "--ores-capabilities=PROCESS_INFO"));
        assertTrue(capabilities.getMessage().contains("cannot be overridden"));

        IllegalArgumentException wallTime = assertThrows(IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        "--ores-max-wall-ms=999999"));
        assertTrue(wallTime.getMessage().contains("cannot be overridden"));

        assertDoesNotThrow(() -> policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(),
                "--ores-code-generation=42"));
    }

    @Test
    void codeGenerationMetadataRejectsDuplicatesAndNegativeValues() {
        IsolatePolicy policy = IsolatePolicy.developer();

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        "--ores-code-generation=41",
                        "--ores-code-generation=42"));
        assertTrue(duplicate.getMessage().contains("duplicate"));

        IllegalArgumentException negative = assertThrows(
                IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        "--ores-code-generation=-1"));
        assertTrue(negative.getMessage().contains("cannot be negative"));
    }

    @Test
    void processGenerationIdsRemainMonotonicAcrossManagerLifetimes() {
        IsolatePolicy policy = IsolatePolicy.developer();

        long firstId;
        try (HotReloadManager first = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            firstId = first.load("generation-a.ores", """
                    pub routine main() => void { return; }
                    """).id();
        }

        try (HotReloadManager second = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            long secondId = second.load("generation-b.ores", """
                    pub routine main() => void { return; }
                    """).id();
            assertTrue(secondId > firstId,
                    "hot-reload generation IDs must stay process-monotonic across manager restarts");
        }
    }

    @Test
    void generationDoesNotExposeRawGraalContext() {
        assertThrows(NoSuchMethodException.class,
                () -> HotReloadManager.Generation.class.getMethod("context"));
    }

    @Test
    void forgedCompiledUnitCannotValidateOneProgramAndExecuteDifferentSource() {
        IsolatePolicy hotLoadOnly = new IsolatePolicy(
                Set.of(
                        IsolatePolicy.Capability.HOT_CODE_LOAD,
                        IsolatePolicy.Capability.STDOUT),
                64L * 1024 * 1024,
                32,
                Duration.ofSeconds(2));

        String maliciousSource = """
                pub routine main() => void {
                  stdio.stdout.write(process.context_id);
                }
                """;
        IncrementalCompiler compiler = new IncrementalCompiler();
        IncrementalCompiler.CompiledUnit malicious =
                compiler.compile(Map.of("forged.ores", maliciousSource))
                        .units()
                        .get("forged.ores");

        var benignProgram = OresCompiler.parseAndTypeCheck("""
                pub routine main() => void { return; }
                """);

        var forged = new IncrementalCompiler.CompiledUnit(
                malicious.unitId(),
                malicious.packageId(),
                malicious.namespace(),
                malicious.sourceDigest(),
                malicious.abiDigest(),
                malicious.dependencies(),
                malicious.sourceText(),
                benignProgram);

        try (HotReloadManager hot =
                     new HotReloadManager(hotLoadOnly, ExecutionProfile.serverJit())) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> hot.load(forged));
            assertTrue(denied.getMessage().contains("PROCESS_INFO"), denied.getMessage());
            assertEquals(0, hot.liveGenerations());
            assertNull(hot.active());
        }
    }

    @Test
    void compiledUnitDigestMismatchIsRejectedBeforeStaging() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        IncrementalCompiler.CompiledUnit valid =
                compiler.compile(Map.of(
                                "digest.ores",
                                "pub routine main() => void { return; }"))
                        .units()
                        .get("digest.ores");

        var forged = new IncrementalCompiler.CompiledUnit(
                valid.unitId(),
                valid.packageId(),
                valid.namespace(),
                "0".repeat(64),
                valid.abiDigest(),
                valid.dependencies(),
                valid.sourceText(),
                valid.program());

        try (HotReloadManager hot =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            IllegalArgumentException mismatch = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.load(forged));
            assertTrue(mismatch.getMessage().contains("digest mismatch"), mismatch.getMessage());
            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void hotReloadCanonicalizesEquivalentCodeUnitPaths() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.load("dir\\..\\same-unit.ores", """
                    pub routine main() => void { return; }
                    """);
            var second = hot.load("same-unit.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertEquals("same-unit.ores", first.codeUnitId());
            assertEquals(first.codeUnitId(), second.codeUnitId());
            assertEquals(second.id(), hot.active("same-unit.ores").id());
            assertEquals(second.id(), hot.active("dir\\..\\same-unit.ores").id());
            assertEquals(1, hot.activeGenerations().size());
        }
    }

    @Test
    void hotReloadRejectsBlankOrCollapsedCodeUnitIdentity() {
        try (HotReloadManager hot =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            assertThrows(IllegalArgumentException.class,
                    () -> hot.load("   ", "pub routine main() => void { return; }"));
            assertThrows(IllegalArgumentException.class,
                    () -> hot.load("a/..", "pub routine main() => void { return; }"));
            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void hotReloadRequiresExplicitCapability() {
        assertThrows(SecurityException.class,
                () -> new HotReloadManager(IsolatePolicy.strictFaas(), ExecutionProfile.serverJit()));
    }

    @Test
    void allExplicitStructuralParameterSpellingsWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Bar {
                  marker: 'brand'
                }

                pub interface Foo extends Bar {
                  markerBrand: 'marking/branding'
                }

                fnc first(y structural Foo) => void {
                  return;
                }

                @AllowStructural(y)
                fnc second(y Foo) => void {
                  return;
                }

                fnc third(@Structural Foo y) => void {
                  return;
                }

                pub routine main() => void {
                  val branded = obj{marker: "brand", markerBrand: "marking/branding"};
                  first(branded);
                  second(branded);
                  third(branded);
                  return;
                }
                """)));
    }

    @Test
    void structuralPermissionIsNotImplicit() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Foo {
                  marker: 'brand'
                }

                fnc nominal(Foo y) => void { return; }

                pub routine main() => void {
                  val branded = obj{marker: "brand"};
                  nominal(branded);
                  return;
                }
                """)));
    }

    @Test
    void extractedMethodValueKeepsReceiverAndSelfCannotBeRebound() throws Exception {
        String output = run("""
                define class Box as
                  val int value;

                  pub get() => int {
                    return self.value;
                  }
                end

                pub routine main() => void {
                  val box = new Box(17);
                  val Fnc<int> callback = box.get;
                  stdio.stdout.write(callback())
                }
                """);
        assertEquals("17", output);

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub bad() => void {
                    self = new Box();
                    return;
                  }
                end
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "receiver.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
