package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Immutable Symbol identity.
 *
 * <p>Oreslang deliberately has three identity domains:</p>
 * <ul>
 *   <li>LOCAL: canonical only inside one actor and reclaimed with that actor.</li>
 *   <li>PROCESS: strongly interned for the trusted OS-process lifetime.</li>
 *   <li>STABLE: UUID identity whose equality/wire identity survives process restart.</li>
 * </ul>
 *
 * <p>No Symbol is a permission to share mutable heap state. Process/stable symbols
 * cross trusted actor heaps as immutable values. Local symbols are non-Send. An
 * adversarial/untrusted isolate is never admitted to any Symbol domain.</p>
 */
public final class OresSymbol {
    public enum Scope { LOCAL, PROCESS, STABLE }

    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_PROCESS_SYMBOLS = 65_536;
    /**
     * Dynamic Symbol.process(...) calls may not consume the whole process table.
     * The remainder is reserved for compiler-known :literal protocol tags so
     * trusted hot-loaded code cannot be starved by earlier dynamic interning.
     */
    public static final int MAX_DYNAMIC_PROCESS_SYMBOLS = 57_344;
    public static final int RESERVED_LITERAL_PROCESS_SYMBOLS =
            MAX_PROCESS_SYMBOLS - MAX_DYNAMIC_PROCESS_SYMBOLS;
    public static final int MAX_STABLE_SYMBOLS = 65_536;
    public static final int MAX_LOCAL_SYMBOLS_PER_ACTOR = 4_096;

    /** Backward-compatible name for the process-table bound. */
    public static final int MAX_LIVE_SYMBOLS = MAX_PROCESS_SYMBOLS;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Object LOCK = new Object();
    private static final Object LOCAL_REGISTRY_KEY = new Object();
    private static final UUID PROCESS_DOMAIN = UUID.randomUUID();
    private static final Map<String, OresSymbol> PROCESS_SYMBOLS = new HashMap<>();
    private static final Map<UUID, OresSymbol> STABLE_SYMBOLS = new HashMap<>();
    private static final AtomicLong NEXT_PROCESS_ID = new AtomicLong(1L);
    private static final AtomicLong NEXT_STABLE_ID = new AtomicLong(1L);
    private static int dynamicProcessSymbols;

    private final Scope scope;
    private final String key;
    private final long id;
    private final UUID domain;

    private OresSymbol(Scope scope, String key, long id, UUID domain) {
        this.scope = Objects.requireNonNull(scope);
        this.key = Objects.requireNonNull(key);
        this.id = id;
        this.domain = Objects.requireNonNull(domain);
    }

    /**
     * Compatibility entry point. Explicit APIs should prefer process(...).
     */
    public static OresSymbol of(String key) {
        return process(key);
    }

    /**
     * Compatibility entry point. Explicit APIs should prefer process(...).
     */
    public static OresSymbol of(String key, IsolatePolicy policy) {
        return process(key, policy);
    }

    /** Strongly canonical process-lifetime identity for trusted execution. */
    public static OresSymbol process(String key) {
        return process(key, IsolatePolicy.developer());
    }

    public static OresSymbol process(String key, IsolatePolicy policy) {
        requireTrusted(policy, "Symbol.process");
        return internProcess(key, false);
    }

    /**
     * Compiler/runtime entry point for a statically known :literal.
     *
     * Dynamic callers intentionally cannot consume the reserved literal slice
     * of the process table. Existing dynamic symbols are still reused so
     * :ready and Symbol.process("ready") are exactly the same identity.
     */
    public static OresSymbol processLiteral(String key, IsolatePolicy policy) {
        requireTrusted(policy, "Symbol literal");
        return internProcess(key, true);
    }

    private static OresSymbol internProcess(String key, boolean compilerLiteral) {
        String normalized = validateKey(key);
        synchronized (LOCK) {
            OresSymbol existing = PROCESS_SYMBOLS.get(normalized);
            if (existing != null) return existing;
            if (PROCESS_SYMBOLS.size() >= MAX_PROCESS_SYMBOLS) {
                throw new IllegalStateException(
                        "process Symbol table limit exceeded: " + MAX_PROCESS_SYMBOLS
                                + "; use actor-local/string data for unbounded dynamic values");
            }
            if (!compilerLiteral && dynamicProcessSymbols >= MAX_DYNAMIC_PROCESS_SYMBOLS) {
                throw new IllegalStateException(
                        "dynamic process Symbol table limit exceeded: " + MAX_DYNAMIC_PROCESS_SYMBOLS
                                + "; " + RESERVED_LITERAL_PROCESS_SYMBOLS
                                + " slots are reserved for compiler-known :literals");
            }
            OresSymbol created = new OresSymbol(
                    Scope.PROCESS, normalized, nextProcessId(), PROCESS_DOMAIN);
            PROCESS_SYMBOLS.put(normalized, created);
            if (!compilerLiteral) dynamicProcessSymbols++;
            return created;
        }
    }

    /**
     * Persistence-safe UUID identity. Stable equality is the UUID itself; the
     * process-local id is only a fast/debug handle and is never serialized.
     */
    public static OresSymbol stable(String uuidKey) {
        return stable(uuidKey, IsolatePolicy.developer());
    }

    public static OresSymbol stable(String uuidKey, IsolatePolicy policy) {
        requireTrusted(policy, "Symbol.stable");
        UUID stableId = validateStableKey(uuidKey);
        synchronized (LOCK) {
            OresSymbol existing = STABLE_SYMBOLS.get(stableId);
            if (existing != null) return existing;
            if (STABLE_SYMBOLS.size() >= MAX_STABLE_SYMBOLS) {
                throw new IllegalStateException(
                        "stable Symbol table limit exceeded: " + MAX_STABLE_SYMBOLS);
            }
            String canonical = stableId.toString();
            OresSymbol created = new OresSymbol(
                    Scope.STABLE, canonical, nextStableId(), stableId);
            STABLE_SYMBOLS.put(stableId, created);
            return created;
        }
    }

    /**
     * Actor-owned identity. Same-key values are canonical only within the current
     * actor and the whole local table dies with the actor cell.
     */
    public static OresSymbol local(
            String key,
            ActorRuntime runtime,
            IsolatePolicy policy) {
        requireTrusted(policy, "Symbol.local");
        Objects.requireNonNull(runtime, "runtime");
        String normalized = validateKey(key);
        ActorRuntime.ActorId actorId = runtime.currentActorId();
        if (actorId == null) {
            throw new IllegalStateException(
                    "Symbol.local(...) is only valid while executing an actor");
        }

        LocalRegistry registry = runtime.currentActorLocal(
                LOCAL_REGISTRY_KEY,
                () -> new LocalRegistry(actorId));
        if (registry == null || !registry.actorId.equals(actorId)) {
            throw new IllegalStateException("actor-local Symbol registry is unavailable");
        }
        OresSymbol existing = registry.symbols.get(normalized);
        if (existing != null) return existing;
        if (registry.symbols.size() >= MAX_LOCAL_SYMBOLS_PER_ACTOR) {
            throw new IllegalStateException(
                    "actor-local Symbol limit exceeded: " + MAX_LOCAL_SYMBOLS_PER_ACTOR);
        }
        OresSymbol created = new OresSymbol(
                Scope.LOCAL, normalized, registry.nextId(), actorId.value());
        registry.symbols.put(normalized, created);
        return created;
    }

    public Scope scope() {
        return scope;
    }

    public String key() {
        return key;
    }

    /** Process-local monotonic handle. Never serialize this value. */
    public long id() {
        return id;
    }

    /** Identity-domain id; useful for diagnostics, never as a wire format. */
    public UUID domainId() {
        return domain;
    }

    /** Compatibility/readability alias for identifier-style protocol tags. */
    public String name() {
        return key;
    }

    public boolean sendable() {
        return scope != Scope.LOCAL;
    }

    public boolean processStable() {
        return scope == Scope.PROCESS || scope == Scope.STABLE;
    }

    public Wire toWire() {
        if (scope == Scope.LOCAL) {
            throw new IllegalStateException(
                    "actor-local Symbols are not serializable or Sendable");
        }
        return new Wire(scope, key);
    }

    public static OresSymbol fromWire(Wire wire, IsolatePolicy policy) {
        Objects.requireNonNull(wire, "wire");
        requireTrusted(policy, "Symbol wire decode");
        return switch (wire.scope()) {
            case PROCESS -> process(wire.key(), policy);
            case STABLE -> stable(wire.key(), policy);
            case LOCAL -> throw new IllegalArgumentException(
                    "actor-local Symbol wire values are forbidden");
        };
    }

    public static int processRegistryEntries() {
        synchronized (LOCK) {
            return PROCESS_SYMBOLS.size();
        }
    }

    public static int stableRegistryEntries() {
        synchronized (LOCK) {
            return STABLE_SYMBOLS.size();
        }
    }

    public static int liveRegistryEntries() {
        synchronized (LOCK) {
            return PROCESS_SYMBOLS.size() + STABLE_SYMBOLS.size();
        }
    }

    private static long nextProcessId() {
        return nextMonotonicId(NEXT_PROCESS_ID, "process Symbol");
    }

    private static long nextStableId() {
        return nextMonotonicId(NEXT_STABLE_ID, "stable Symbol");
    }

    private static long nextMonotonicId(AtomicLong counter, String domain) {
        while (true) {
            long current = counter.get();
            if (current <= 0 || current == Long.MAX_VALUE) {
                throw new IllegalStateException(domain + " id space exhausted");
            }
            if (counter.compareAndSet(current, current + 1)) return current;
        }
    }

    private static void requireTrusted(IsolatePolicy policy, String api) {
        Objects.requireNonNull(policy, "policy");
        if (policy.adversarial()) {
            throw new SecurityException(
                    "Symbols are unavailable to adversarial/untrusted isolates: " + api);
        }
    }

    private static String validateKey(String key) {
        Objects.requireNonNull(key, "symbol key");
        if (key.isBlank()) throw new IllegalArgumentException("symbol key cannot be blank");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "symbol key exceeds " + MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= key.length() || !Character.isLowSurrogate(key.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "symbol key cannot contain unpaired surrogate characters");
                }
                i++;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "symbol key cannot contain unpaired surrogate characters");
            }
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        "symbol key cannot contain control characters");
            }
        }
        return key;
    }

    private static UUID validateStableKey(String key) {
        String validated = validateKey(key);
        if (validated.length() != 36) {
            throw new IllegalArgumentException(
                    "stable Symbol key must be a canonical UUID");
        }
        final UUID uuid;
        try {
            uuid = UUID.fromString(validated);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "stable Symbol key must be a canonical UUID", invalid);
        }
        String canonical = uuid.toString();
        if (!canonical.equals(validated.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "stable Symbol key must be a canonical UUID");
        }
        return uuid;
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OresSymbol symbol) || symbol.scope != scope) return false;
        if (scope == Scope.STABLE) return domain.equals(symbol.domain);
        return id == symbol.id && domain.equals(symbol.domain);
    }

    @Override
    public int hashCode() {
        if (scope == Scope.STABLE) return Objects.hash(scope, domain);
        return Objects.hash(scope, domain, id);
    }

    @Override
    public String toString() {
        return switch (scope) {
            case PROCESS -> IDENTIFIER.matcher(key).matches()
                    ? ":" + key
                    : "Symbol.process(\"" + escaped(key) + "\")";
            case STABLE -> "Symbol.stable(\"" + escaped(key) + "\")";
            case LOCAL -> "Symbol.local(\"" + escaped(key) + "\")";
        };
    }

    public record Wire(Scope scope, String key) {
        public Wire {
            Objects.requireNonNull(scope, "scope");
            if (scope == Scope.LOCAL) {
                throw new IllegalArgumentException(
                        "actor-local Symbol wire values are forbidden");
            }
            key = scope == Scope.STABLE
                    ? validateStableKey(key).toString()
                    : validateKey(key);
        }
    }

    private static final class LocalRegistry {
        private final ActorRuntime.ActorId actorId;
        private final Map<String, OresSymbol> symbols = new HashMap<>();
        private long nextLocalId = 1L;

        private LocalRegistry(ActorRuntime.ActorId actorId) {
            this.actorId = Objects.requireNonNull(actorId);
        }

        private long nextId() {
            if (nextLocalId <= 0 || nextLocalId == Long.MAX_VALUE) {
                throw new IllegalStateException("actor-local Symbol id space exhausted");
            }
            return nextLocalId++;
        }
    }
}
