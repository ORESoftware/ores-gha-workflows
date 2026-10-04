package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
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
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
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

    @Test
    void failedActorDoesNotKillSiblingOrJoiner() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch siblingReceived = new CountDownLatch(1);

            var bad = runtime.<String>spawn(() -> (message, context) -> {
                throw new IllegalStateException("boom");
            });
            var good = runtime.<String>spawn(() -> (message, context) -> {
                siblingReceived.countDown();
            });

            bad.send("explode");
            bad.join();

            assertFalse(bad.isAlive());
            assertTrue(bad.failed());
            assertInstanceOf(IllegalStateException.class, bad.failure());

            good.send("still-alive");
            assertTrue(siblingReceived.await(2, TimeUnit.SECONDS));
            good.stop();
            good.join();
            assertFalse(good.failed());
        }
    }

    @Test
    void behaviorFactoryFailureIsRecordedAndDoesNotLeakActorCell() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(() -> {
                throw new IllegalStateException("factory boom");
            });

            ref.join();

            assertFalse(ref.isAlive());
            assertTrue(ref.failed());
            assertInstanceOf(IllegalStateException.class, ref.failure());
            assertThrows(IllegalStateException.class, () -> ref.send("late"));
        }
    }

    @Test
    void gracefulStopDrainsMessagesQueuedBeforeStop() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(2);
            var ref = runtime.<String>spawn(() -> (message, context) -> received.countDown());

            ref.send("one");
            ref.send("two");
            ref.stop();
            ref.join();

            assertEquals(0, received.getCount());
            assertFalse(ref.isAlive());
            assertFalse(ref.failed());
        }
    }

    @Test
    void runtimeShutdownCancellationDoesNotMarkActorFailed() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch started = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            while (true) {
                context.runtime().schedulerSafepoint();
            }
        });

        ref.send("run");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        runtime.close();
        ref.join();

        assertFalse(ref.isAlive());
        assertFalse(ref.failed());
    }

    @Test
    void actorKindIsObservableWithoutChangingFailureIsolation() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.<String>spawn(ActorRuntime.ActorKind.SHARED, () -> (message, context) -> { });
            var isolated = runtime.<String>spawn(ActorRuntime.ActorKind.ISOLATE, () -> (message, context) -> { });

            assertEquals(ActorRuntime.ActorKind.SHARED, shared.kind());
            assertEquals(ActorRuntime.ActorKind.ISOLATE, isolated.kind());

            shared.stop();
            isolated.stop();
            shared.join();
            isolated.join();
        }
    }

    @Test
    void stopAtomicallyRejectsLaterSends() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            var ref = runtime.<String>spawn(() -> (message, context) -> {
                started.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timeout");
                }
            });

            ref.send("accepted");
            assertTrue(started.await(2, TimeUnit.SECONDS));

            ref.stop();
            assertThrows(IllegalStateException.class, () -> ref.send("too-late"));

            release.countDown();
            ref.join();
            assertFalse(ref.isAlive());
            assertFalse(ref.failed());
        }
    }

    @Test
    void stopDrainsFullMailboxEvenWithoutSpaceForStopSentinel() throws Exception {
        IsolatePolicy ceiling = IsolatePolicy.developer();
        IsolatePolicy oneMessageMailbox = new IsolatePolicy(
                ceiling.capabilities(),
                ceiling.maxHeapBytes(),
                1,
                ceiling.maxWallTime(),
                ceiling.adversarial());

        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(2);

            var ref = runtime.<String>spawn(oneMessageMailbox, () -> (message, context) -> {
                if (message.equals("one")) {
                    firstStarted.countDown();
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timeout");
                    }
                }
                delivered.countDown();
            });

            ref.send("one");
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            ref.send("two"); // fills the one-slot mailbox
            ref.stop();      // no room for STOP sentinel; stopRequested must still work
            releaseFirst.countDown();

            ref.join();
            assertEquals(0, delivered.getCount());
            assertFalse(ref.failed());
        }
    }
}
