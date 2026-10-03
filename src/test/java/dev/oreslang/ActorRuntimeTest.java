package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ExecutionTerminated;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresValues;
import dev.oreslang.runtime.ProcessSingletonRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void actorLocalRuntimeStatePersistsPerActorAndNeverAliasesAcrossActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger initializations = new AtomicInteger();
            Map<ActorRuntime.ActorId, Integer> lastValue = new ConcurrentHashMap<>();
            CountDownLatch delivered = new CountDownLatch(3);

            java.util.function.Supplier<ActorRuntime.Behavior<String>> factory = () -> (message, context) -> {
                assertEquals(context.self().id(), context.runtime().currentActorId());
                AtomicInteger local = context.runtime().currentActorLocal(
                        "module:test",
                        () -> {
                            initializations.incrementAndGet();
                            return new AtomicInteger();
                        });
                lastValue.put(context.self().id(), local.incrementAndGet());
                delivered.countDown();
            };

            var first = runtime.<String>spawn(factory);
            var second = runtime.<String>spawn(factory);

            first.send("one");
            first.send("two");
            second.send("one");

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(2, initializations.get());
            assertEquals(2, lastValue.get(first.id()));
            assertEquals(1, lastValue.get(second.id()));
        }
    }

    @Test
    void failedActorLocalInitializationCanRetryOnTheSameActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicReference<Integer> observed = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(2);

            var ref = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    Integer value = context.runtime().currentActorLocal(
                            "retry-local",
                            () -> {
                                int attempt = attempts.incrementAndGet();
                                if (attempt == 1) throw new IllegalStateException("first init fails");
                                return 42;
                            });
                    observed.set(value);
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("first init fails"));
                } finally {
                    delivered.countDown();
                }
            });

            ref.send("first");
            ref.send("second");

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(42, observed.get());
        }
    }

    @Test
    void recursiveActorLocalInitializationFailsFastAndDoesNotPoisonActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(2);
            AtomicReference<String> cycleMessage = new AtomicReference<>();
            AtomicReference<Integer> recovered = new AtomicReference<>();

            var ref = runtime.<String>spawn(() -> (message, context) -> {
                if (message.equals("cycle")) {
                    try {
                        context.runtime().currentActorLocal(
                                "cycle-local",
                                () -> context.runtime().currentActorLocal(
                                        "cycle-local",
                                        () -> 1));
                    } catch (IllegalStateException cycle) {
                        cycleMessage.set(cycle.getMessage());
                    }
                } else {
                    recovered.set(context.runtime().currentActorLocal("cycle-local", () -> 7));
                }
                delivered.countDown();
            });

            ref.send("cycle");
            ref.send("recover");

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertNotNull(cycleMessage.get());
            assertTrue(cycleMessage.get().contains("initialization cycle"));
            assertEquals(7, recovered.get());
        }
    }

    @Test
    void spawnRejectsNullPolicyAndFactorySynchronously() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(NullPointerException.class,
                    () -> runtime.<String>spawn(null, () -> (message, context) -> { }));
            assertThrows(NullPointerException.class,
                    () -> runtime.<String>spawn(IsolatePolicy.developer(), null));
        }
    }

    @Test
    void freezesMessagesBeforeDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();
            var ref = runtime.<List<Integer>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            ref.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) observed.get()).add(9));
        }
    }

    @Test
    void repeatedMutableSourceReferencesDoNotCreateReceiverAliases() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();

            var ref = runtime.<List<Object>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> sharedMutable = new ArrayList<>(List.of(1, 2));
            ref.send(List.of(sharedMutable, sharedMutable));
            sharedMutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(2, observed.get().size());
            assertEquals(List.of(1, 2), observed.get().get(0));
            assertEquals(List.of(1, 2), observed.get().get(1));
            assertNotSame(observed.get().get(0), observed.get().get(1),
                    "ordinary actor message copying must not preserve mutable source aliases");
        }
    }

    @Test
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
    }

    @Test
    void rejectsCyclicMessageGraphsInsteadOfRecursingForever() {
        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cyclic));
        assertTrue(error.getMessage().contains("cyclic"));
    }

    @Test
    void publicSharedWrapperCannotSmuggleMutableAliases() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        ActorRuntime.Shared<ArrayList<Integer>> untrustedWrapper = new ActorRuntime.Shared<>(mutable);

        @SuppressWarnings("unchecked")
        ActorRuntime.Shared<List<Integer>> frozen =
                (ActorRuntime.Shared<List<Integer>>) ActorRuntime.freeze(untrustedWrapper);

        mutable.add(3);
        assertEquals(List.of(1, 2), frozen.value());
        assertThrows(UnsupportedOperationException.class, () -> frozen.value().add(9));
    }

    @Test
    void rejectsPathologicallyDeepMessageGraphs() {
        Object value = 1;
        for (int i = 0; i < 300; i++) value = List.of(value);

        Object nested = value;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(nested));
        assertTrue(error.getMessage().contains("nesting depth"));
    }

    @Test
    void freezePreservesDistinctNullValuedMapEntries() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("first", null);
        source.put("second", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) ActorRuntime.freeze(source);

        assertEquals(2, frozen.size());
        assertTrue(frozen.containsKey("first"));
        assertTrue(frozen.containsKey("second"));
        assertNull(frozen.get("first"));
        assertNull(frozen.get("second"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("third", null));
    }

    @Test
    void freezeEnforcesApproximateByteBudgetEvenForSingleScalars() {
        String oversized = "x".repeat((8 * 1024 * 1024) + 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(oversized));

        assertTrue(error.getMessage().contains("maximum frozen size"));
    }

    @Test
    void publicMaterializationValidatesBeforeProducingOwnedMutableCopies() {
        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.materializeOwned(cyclic));
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.materializeOwned(new StringBuilder("host mutable")));

        ArrayList<Integer> original = new ArrayList<>(List.of(1, 2));
        @SuppressWarnings("unchecked")
        List<Integer> owned = (List<Integer>) ActorRuntime.materializeOwned(original);
        original.add(3);
        owned.add(9);

        assertEquals(List.of(1, 2, 3), original);
        assertEquals(List.of(1, 2, 9), owned);
    }

    @Test
    void actorFactoryFailureRemovesDeadCellFromRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch attempted = new CountDownLatch(1);
            var ref = runtime.<String>spawn(() -> {
                attempted.countDown();
                throw new IllegalStateException("factory failed");
            });

            assertTrue(attempted.await(1, TimeUnit.SECONDS));
            assertEventuallyUnknownActor(ref);
        }
    }

    @Test
    void actorBehaviorFailureRemovesDeadCellAndRejectsLaterMessages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                entered.countDown();
                throw new IllegalStateException("boom");
            });

            ref.send("crash");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEventuallyUnknownActor(ref);
        }
    }

    @Test
    void freezeRejectsGraphsThatExceedNodeBudget() {
        List<Integer> tooManyNodes = Collections.nCopies(100_001, 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooManyNodes));

        assertTrue(error.getMessage().contains("maximum graph size"), error.getMessage());
    }

    @Test
    void freezeRejectsSetElementsThatCollideOnlyAfterFreezing() {
        ActorRuntime.Shared<int[]> first = new ActorRuntime.Shared<>(new int[] {1});
        ActorRuntime.Shared<int[]> second = new ActorRuntime.Shared<>(new int[] {1});
        LinkedHashSet<Object> source = new LinkedHashSet<>();
        source.add(first);
        source.add(second);
        assertEquals(2, source.size());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(source));

        assertTrue(error.getMessage().contains("set elements collide"), error.getMessage());
    }

    @Test
    void freezeRejectsMapKeysThatCollideOnlyAfterFreezing() {
        ActorRuntime.Shared<int[]> first = new ActorRuntime.Shared<>(new int[] {1});
        ActorRuntime.Shared<int[]> second = new ActorRuntime.Shared<>(new int[] {1});
        LinkedHashMap<Object, String> source = new LinkedHashMap<>();
        source.put(first, "first");
        source.put(second, "second");
        assertEquals(2, source.size());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(source));

        assertTrue(error.getMessage().contains("map keys collide"), error.getMessage());
    }

    @Test
    void actorRefsCannotCrossRuntimePolicyBoundaries() throws Exception {
        try (ActorRuntime source = new ActorRuntime(IsolatePolicy.developer());
             ActorRuntime destination = new ActorRuntime(IsolatePolicy.strictFaas())) {
            var sourceRef = source.<String>spawn(() -> (message, context) -> { });
            var destinationRef = destination.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException crossRuntime = assertThrows(IllegalArgumentException.class,
                    () -> destinationRef.send(sourceRef));
            assertTrue(crossRuntime.getMessage().contains("owning ActorRuntime"));

            IllegalArgumentException contextFree = assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(sourceRef));
            assertTrue(contextFree.getMessage().contains("owning ActorRuntime"));
        }
    }

    @Test
    void foreignActorRefCannotBeSmuggledInsideNestedContainersOrShared() {
        try (ActorRuntime source = new ActorRuntime();
             ActorRuntime destination = new ActorRuntime()) {
            var foreign = source.<String>spawn(() -> (message, context) -> { });
            var receiver = destination.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException nested = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(List.of(Map.of("ref", foreign))));
            assertTrue(nested.getMessage().contains("owning ActorRuntime"));

            ActorRuntime.Shared<Object> wrapped =
                    new ActorRuntime.Shared<>(List.of(foreign));
            IllegalArgumentException shared = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(wrapped));
            assertTrue(shared.getMessage().contains("owning ActorRuntime"));
        }
    }

    @Test
    void actorRefsRemainSendableInsideTheirOwningRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();
            var target = runtime.<String>spawn(() -> (message, context) -> { });
            var receiver = runtime.<Object>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            receiver.send(target);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertSame(target, observed.get());
        }
    }

    @Test
    void runtimeOwnedOptionValuesDeepFreezeWithTheOuterBudget() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        OresValues.OptionValue source = new OresValues.OptionValue(true, mutable);

        OresValues.OptionValue frozen = (OresValues.OptionValue) ActorRuntime.freeze(source);
        mutable.add(3);

        assertEquals(List.of(1, 2), frozen.value());
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) frozen.value()).add(9));
    }

    @Test
    void directSendRejectsForeignRuntimeActorRefs() {
        try (ActorRuntime source = new ActorRuntime();
             ActorRuntime destination = new ActorRuntime()) {
            var sourceRef = source.<String>spawn(() -> (message, context) -> { });

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> destination.send(sourceRef, "nope"));
            assertTrue(error.getMessage().contains("different ActorRuntime"));
        }
    }

    @Test
    void actorMessageWallTimeIsEnforcedAtSchedulerSafepoints() throws Exception {
        IsolatePolicy shortPolicy = new IsolatePolicy(
                Set.of(),
                32L * 1024 * 1024,
                8,
                Duration.ofMillis(40));
        try (ActorRuntime runtime = new ActorRuntime(shortPolicy)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch stopped = new CountDownLatch(1);

            var ref = runtime.<String>spawn(shortPolicy, () -> (message, context) -> {
                started.countDown();
                try {
                    while (true) context.runtime().schedulerSafepoint();
                } finally {
                    stopped.countDown();
                }
            });

            ref.send("run");
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(stopped.await(1, TimeUnit.SECONDS),
                    "actor ignored its maxWallTime scheduler deadline");
        }
    }

    @Test
    void runtimeCloseInterruptsAndDrainsCooperativeActors() throws Exception {
        ActorRuntime runtime = new ActorRuntime(new IsolatePolicy(
                Set.of(),
                32L * 1024 * 1024,
                8,
                Duration.ofSeconds(1)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            try {
                while (true) context.runtime().schedulerSafepoint();
            } finally {
                stopped.countDown();
            }
        });
        ref.send("run");
        assertTrue(started.await(1, TimeUnit.SECONDS));

        runtime.close();

        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertThrows(ExecutionTerminated.class,
                () -> runtime.shareReadonly(List.of(1)));
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
    }

    @Test
    void actorMailboxBackpressureRejectsBeyondConfiguredCapacity() throws Exception {
        IsolatePolicy tinyMailbox = new IsolatePolicy(
                Set.of(),
                32L * 1024 * 1024,
                1,
                Duration.ofSeconds(1));

        try (ActorRuntime runtime = new ActorRuntime(tinyMailbox)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            var ref = runtime.<String>spawn(tinyMailbox, () -> (message, context) -> {
                entered.countDown();
                release.await(1, TimeUnit.SECONDS);
            });

            ref.send("active");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            ref.send("queued");

            IllegalStateException full = assertThrows(IllegalStateException.class,
                    () -> ref.send("overflow"));
            assertTrue(full.getMessage().contains("mailbox limit"));

            release.countDown();
        }
    }

    @Test
    void noneCannotHidePayloadAndFrozenSharedStaysReadonlyAfterMaterialization() {
        assertThrows(IllegalArgumentException.class,
                () -> new OresValues.OptionValue(false, "hidden"));

        ActorRuntime.Shared<List<Integer>> shared =
                new ActorRuntime.Shared<>(new ArrayList<>(List.of(1, 2)));

        @SuppressWarnings("unchecked")
        ActorRuntime.Shared<List<Integer>> owned =
                (ActorRuntime.Shared<List<Integer>>) ActorRuntime.materializeOwned(shared);

        assertEquals(List.of(1, 2), owned.value());
        assertThrows(UnsupportedOperationException.class,
                () -> owned.value().add(3));
    }

    @Test
    void actorToSingletonRpcInheritsRemainingMessageDeadline() throws Exception {
        IsolatePolicy shortPolicy = new IsolatePolicy(
                Set.of(),
                32L * 1024 * 1024,
                8,
                Duration.ofMillis(90));
        ProcessSingletonRegistry.Handle<Object> singleton =
                ProcessSingletonRegistry.getOrCreate(
                        "actor-deadline:" + java.util.UUID.randomUUID(),
                        Object::new);

        try (ActorRuntime runtime = new ActorRuntime(shortPolicy)) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawn(shortPolicy, () -> (message, context) -> {
                try {
                    Thread.sleep(60);
                    singleton.call(
                            List.of(),
                            8,
                            context.runtime().remainingCurrentActorWallTime(Duration.ofSeconds(5)),
                            (state, ignored) -> {
                                while (true) ProcessSingletonRegistry.checkExecutionBudget();
                            })
                            .toCompletableFuture()
                            .join();
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    finished.countDown();
                }
            });

            ref.send("run");

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertNotNull(observed.get());
            Throwable current = observed.get();
            boolean deadline = false;
            while (current != null) {
                if (String.valueOf(current.getMessage()).contains("wall-time budget")
                        || String.valueOf(current.getMessage()).contains("expired")) {
                    deadline = true;
                    break;
                }
                current = current.getCause();
            }
            assertTrue(deadline, String.valueOf(observed.get()));
        }
    }

    @Test
    void actorCanCloseItsOwnRuntimeWithoutDeadlocking() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch returnedFromClose = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            entered.countDown();
            context.runtime().close();
            returnedFromClose.countDown();
        });

        ref.send("shutdown");

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(returnedFromClose.await(1, TimeUnit.SECONDS),
                "actor deadlocked while closing its own runtime");
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
        assertThrows(IllegalStateException.class,
                () -> runtime.<String>spawn(() -> (message, context) -> { }));
    }

    @Test
    void sendRacingWithRuntimeCloseNeverSucceedsAfterCloseReturns() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
        });

        ref.send("block");
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletableFuture<Void> closing = CompletableFuture.runAsync(runtime::close);
        for (int i = 0; i < 100 && !closing.isDone(); i++) {
            try {
                ref.send("racing");
            } catch (IllegalStateException expected) {
                break;
            }
            Thread.yield();
        }

        release.countDown();
        closing.join();

        for (int i = 0; i < 16; i++) {
            assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
        }
    }

    @Test
    void actorCarriesItsOwnStricterPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> observed = new AtomicReference<>();
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                observed.set(context.policy());
                received.countDown();
            });
            ref.send("ping");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(strict.capabilities(), observed.get().capabilities());
            assertEquals(strict.maxMailboxMessages(), observed.get().maxMailboxMessages());
        }
    }

    @Test
    void childActorCannotEscalatePastRuntimePolicyCeiling() {
        IsolatePolicy ceiling = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            IsolatePolicy escalated = ceiling.withCapabilities(IsolatePolicy.Capability.PROCESS_INFO);
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawn(escalated, () -> (message, context) -> { }));
        }
    }

    @Test
    void readonlySharingDeepFreezesContainers() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));
            assertNotNull(shared.value());
        }
    }
    private static void assertEventuallyUnknownActor(ActorRuntime.ActorRef<String> ref) throws Exception {
        IllegalStateException last = null;
        for (int i = 0; i < 100; i++) {
            try {
                ref.send("probe");
            } catch (IllegalStateException failure) {
                if (failure.getMessage().contains("unknown actor")) return;
                last = failure;
            }
            Thread.sleep(10);
        }
        fail("actor cell remained addressable after failure" + (last == null ? "" : ": " + last.getMessage()));
    }

}
