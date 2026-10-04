package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnRuntimeTest {

    @Test
    void spawnInvocationSeparatesReadyFromCompletion() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message + "-done";
                    });

            assertNotNull(spawn.id());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(spawn.ready().isDone(), "READY must precede actor callable execution");
            assertFalse(spawn.result().isDone(), "spawn must not wait for callable completion");

            release.countDown();
            assertEquals("work-done", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void spawnInvocationReturnsBeforeActorCallableCompletes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorSpawn<Integer, Integer> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    7,
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message * 6;
                    });

            assertNotNull(spawn.id());
            assertFalse(spawn.result().isDone());
            release.countDown();
            assertEquals(42, spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void doneTracksCompletionIndependentlyOfReady() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "ok",
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message;
                    });

            assertEquals(spawn.id(), spawn.ready().get(2, TimeUnit.SECONDS).id());
            assertFalse(spawn.done().isDone());

            release.countDown();
            assertTrue(spawn.done().get(2, TimeUnit.SECONDS));
            assertEquals("ok", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void readinessFutureYieldsTheSameActorIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "ok",
                    (message, context) -> message);

            ActorRuntime.ActorRef<String> ref = spawn.ready().get(2, TimeUnit.SECONDS);
            assertEquals(spawn.id(), ref.id());
            assertEquals("ok", spawn.result().get(2, TimeUnit.SECONDS));
            assertFalse(
                    ref.isAlive(),
                    "published result must imply the one-shot actor has fully finalized");
        }
    }

    @Test
    void watchdogFailureDominatesCallableThatIgnoresInterruptAndReturnsLate() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(1),
                TimeUnit.MILLISECONDS.toNanos(20),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "late-success",
                    (message, context) -> {
                        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80);
                        while (System.nanoTime() < until) {
                            // Deliberately consume and ignore the watchdog interrupt.
                            Thread.interrupted();
                        }
                        return message;
                    });

            ActorRuntime.ActorRef<String> ref = spawn.ready().get(2, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.result().get(2, TimeUnit.SECONDS));

            assertInstanceOf(ActorRuntime.ActorTurnExceededException.class, failure.getCause());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    ref.failure().orElseThrow());
        }
    }

    @Test
    void callableFailureFailsResultAndRecordsActorTerminationCause() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "boom",
                    (message, context) -> {
                        throw new IllegalStateException(message);
                    });

            ActorRuntime.ActorRef<String> ref = spawn.ready().get(2, TimeUnit.SECONDS);

            ExecutionException resultFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.result().get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, resultFailure.getCause());
            assertEquals("boom", resultFailure.getCause().getMessage());

            ExecutionException doneFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.done().get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, doneFailure.getCause());

            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, ref.failure().orElseThrow());
        }
    }

    @Test
    void actorSpawnTicketCannotCrossActorBoundary() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message;
                    });

            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> { });

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> target.send(spawn));
            assertTrue(denied.getMessage().contains("ActorSpawn"));

            release.countDown();
            assertEquals("work", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void stopAtReadyBoundarySettlesResultAndDoneInsteadOfLeavingThemPending() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                Long.MAX_VALUE,
                64);
        CountDownLatch allowEntry = new CountDownLatch(1);
        ActorRuntime.TurnExecutor gatedEntry = turn -> {
            try {
                if (!allowEntry.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to enter actor turn");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "interrupted before actor turn entry");
            }
            turn.run();
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config,
                gatedEntry)) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "never-delivered",
                    (message, context) -> fail("callable must not run after READY-boundary stop"));

            spawn.ready().whenComplete((ref, failure) -> {
                if (failure == null) ref.stop();
            });
            allowEntry.countDown();

            assertEquals(spawn.id(), spawn.ready().get(2, TimeUnit.SECONDS).id());

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> spawn.result().get(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> spawn.done().get(2, TimeUnit.SECONDS));
            assertTrue(spawn.result().isDone());
            assertTrue(spawn.done().isDone());
        }
    }

    @Test
    void cancellingChildSpawnFromSingleCarrierActorNeverBlocksTheCarrier() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                Long.MAX_VALUE,
                64);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch parentCompleted = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivateTrusted(
                    context -> (message, turn) -> {
                        ActorRuntime.ActorSpawn<String, String> child =
                                turn.runtime().spawnInvocation(
                                        ActorRuntime.ActorKind.PRIVATE,
                                        "child",
                                        (childMessage, childContext) -> childMessage);

                        assertTrue(child.result().cancel(true));
                        parentCompleted.countDown();
                        turn.self().stop();
                    });

            parent.send("go");

            assertTrue(
                    parentCompleted.await(1, TimeUnit.SECONDS),
                    "Future cancellation from an actor turn must only request child stop; "
                            + "it must never synchronously wait for child finalization");
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void contextEntryWatchdogSettlesReadyAndResultWithoutWaitingForCarrierUnwind() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(40),
                1,
                64);

        ReentrantLock contextGate = new ReentrantLock(true);
        contextGate.lock();
        ActorRuntime.TurnExecutor interruptibleEntry = turn -> {
            boolean locked = false;
            try {
                contextGate.lockInterruptibly();
                locked = true;
                turn.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "interrupted before actor context entry");
            } finally {
                if (locked) contextGate.unlock();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config,
                interruptibleEntry)) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> fail("guest callback must not run"));

            ExecutionException readyFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.ready().get(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    readyFailure.getCause());

            ExecutionException resultFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.result().get(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    resultFailure.getCause());
        } finally {
            contextGate.unlock();
        }
    }

}
