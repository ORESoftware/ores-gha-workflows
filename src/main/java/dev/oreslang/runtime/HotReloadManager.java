package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.IncrementalCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Versioned source hot loader.
 *
 * Loading is side-effect free with respect to guest execution: source is
 * parsed/type/capability checked, then assigned a fresh context. The trusted
 * supervisor explicitly starts the generation after activation.
 *
 * No JNI/FFI or OS dynamic-library loading is required.
 */
public final class HotReloadManager implements AutoCloseable {
    private static final AtomicLong PROCESS_GENERATION_SEQUENCE = new AtomicLong();

    private final IsolatePolicy policy;
    private final ExecutionProfile executionProfile;
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();

    public HotReloadManager(IsolatePolicy policy, ExecutionProfile executionProfile) {
        this.policy = policy;
        this.executionProfile = executionProfile;
        if (!policy.allows(IsolatePolicy.Capability.HOT_CODE_LOAD)) {
            throw new SecurityException("HOT_CODE_LOAD capability is required");
        }
    }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        requireOpen();
        OresCompiler.validateForIsolate(sourceText, policy);
        return stage(name, digest(sourceText), sourceText);
    }

    /**
     * Loads an incrementally compiled unit across a trust boundary.
     *
     * CompiledUnit is externally constructible, so its AST/digest metadata is
     * advisory only here. Recompute source integrity and repeat syntax, type,
     * and capability admission against the exact source that will execute.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        requireOpen();
        java.util.Objects.requireNonNull(unit, "unit");
        String actualDigest = digest(unit.sourceText());
        if (!actualDigest.equals(unit.sourceDigest())) {
            throw new IllegalArgumentException("compiled unit source digest mismatch for " + unit.unitId());
        }
        OresCompiler.validateForIsolate(unit.sourceText(), policy);
        return stage(unit.unitId(), actualDigest, unit.sourceText());
    }

    private Generation stage(String codeUnitId, String sourceDigest, String sourceText) {
        requireOpen();
        codeUnitId = normalizeCodeUnitId(codeUnitId);
        long id = PROCESS_GENERATION_SEQUENCE.incrementAndGet();
        Context context = policy.restrictedContextBuilder(
                executionProfile,
                "--ores-code-generation=" + id).build();
        try {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            Generation generation = new Generation(
                    this, id, codeUnitId, sourceDigest, context, source, executionProfile);
            generations.put(id, generation);
            activeByCodeUnit.put(codeUnitId, generation);
            active.set(generation);
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                context.close(true);
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public Generation loadAndStart(String name, String sourceText) {
        Generation generation = load(name, sourceText);
        generation.start();
        return generation;
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("hot reload manager is closed");
    }

    /** Last generation staged, retained for compatibility with the single-unit API. */
    public Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(normalizeCodeUnitId(codeUnitId));
    }

    public synchronized Map<String, Generation> activeGenerations() {
        return Map.copyOf(activeByCodeUnit);
    }

    /** Explicit retirement permits old actors/requests to drain before teardown. */
    public synchronized void retire(long generationId) {
        Generation generation = generations.get(generationId);
        if (generation == null) return;
        detach(generation);
        generation.closeContextOnly();
    }

    private synchronized void failedStart(Generation generation) {
        if (generations.get(generation.id()) == generation) detach(generation);
        generation.closeContextOnly();
    }

    private void detach(Generation generation) {
        generations.remove(generation.id(), generation);

        if (activeByCodeUnit.get(generation.codeUnitId()) == generation) {
            Generation replacement = latestLiveForCodeUnit(generation.codeUnitId());
            if (replacement == null) activeByCodeUnit.remove(generation.codeUnitId(), generation);
            else activeByCodeUnit.put(generation.codeUnitId(), replacement);
        }

        if (active.get() == generation) active.set(latestLiveGeneration());
    }

    private Generation latestLiveForCodeUnit(String codeUnitId) {
        Generation latest = null;
        for (Generation candidate : generations.values()) {
            if (!candidate.codeUnitId().equals(codeUnitId) || candidate.closed()) continue;
            if (latest == null || candidate.id() > latest.id()) latest = candidate;
        }
        return latest;
    }

    private Generation latestLiveGeneration() {
        Generation latest = null;
        for (Generation candidate : generations.values()) {
            if (candidate.closed()) continue;
            if (latest == null || candidate.id() > latest.id()) latest = candidate;
        }
        return latest;
    }

    public synchronized int liveGenerations() { return generations.size(); }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        Generation[] live = generations.values().toArray(Generation[]::new);
        generations.clear();
        activeByCodeUnit.clear();
        active.set(null);

        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        for (Generation generation : live) {
            try {
                generation.closeContextOnly();
            } catch (RuntimeException failure) {
                if (runtimeFailure == null && errorFailure == null) runtimeFailure = failure;
                else if (runtimeFailure != null) runtimeFailure.addSuppressed(failure);
                else errorFailure.addSuppressed(failure);
            } catch (Error failure) {
                if (runtimeFailure == null && errorFailure == null) errorFailure = failure;
                else if (runtimeFailure != null) runtimeFailure.addSuppressed(failure);
                else errorFailure.addSuppressed(failure);
            }
        }
        if (runtimeFailure != null) throw runtimeFailure;
        if (errorFailure != null) throw errorFailure;
    }

    private static String normalizeCodeUnitId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("hot-reload code unit id cannot be blank");
        }
        try {
            String normalized = Path.of(id.replace('\\', '/'))
                    .normalize()
                    .toString()
                    .replace('\\', '/');
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("hot-reload code unit id cannot normalize to an empty path");
            }
            return normalized;
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("invalid hot-reload code unit id: " + id, invalid);
        }
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Generation implements AutoCloseable {
        private final HotReloadManager owner;
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final Source source;
        private final ExecutionProfile executionProfile;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Generation(
                HotReloadManager owner,
                long id,
                String codeUnitId,
                String sha256,
                Context context,
                Source source,
                ExecutionProfile executionProfile) {
            this.owner = owner;
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.executionProfile = executionProfile;
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        public Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public boolean started() { return started.get(); }
        public boolean closed() { return closed.get(); }

        /** Starts the staged generation exactly once. */
        public Value start() {
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("generation already started");
            try {
                return context.eval(source);
            } catch (RuntimeException | Error failure) {
                try {
                    owner.failedStart(this);
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private void closeContextOnly() {
            if (closed.compareAndSet(false, true)) context.close(true);
        }

        @Override
        public void close() {
            owner.retire(id);
        }
    }
}
