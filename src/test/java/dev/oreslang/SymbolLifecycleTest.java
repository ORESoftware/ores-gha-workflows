package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolLifecycleTest {
    private static final String UUID_KEY = "5d60d832-fc6a-4fa2-a551-1448c2df6bf2";

    @Test
    void colonAndScopedFactoriesTypecheckAsCopySymbols() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module symbols as
                  pub fnc same() => bool {
                    val Symbol literal = :connected;
                    val Symbol compat = new Symbol("1d46c11d-580c-4ee0-b5ff-cb3bbd83d596");
                    val Symbol process = Symbol.process("connected");
                    val Symbol stable = Symbol.stable("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                    val Symbol local = Symbol.local("request-local");
                    return literal == process;
                  }
                end
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt first = (Ast.BindingStmt) fn.body().getFirst();
        Ast.LiteralExpr literal = (Ast.LiteralExpr) first.initializer();
        assertEquals("connected", ((Ast.Symbol) literal.value()).name());
    }

    @Test
    void processAndStableSymbolsAreStronglyCanonicalInTheirDomains() {
        OresSymbol first = OresSymbol.process("shared-key");
        OresSymbol again = OresSymbol.process("shared-key");
        OresSymbol other = OresSymbol.process("other-key");
        OresSymbol stable = OresSymbol.stable(UUID_KEY);
        OresSymbol stableAgain = OresSymbol.stable(UUID_KEY.toUpperCase());

        assertSame(first, again);
        assertEquals(first, again);
        assertEquals(OresSymbol.Scope.PROCESS, first.scope());
        assertEquals("shared-key", first.key());
        assertEquals(first.id(), again.id());
        assertNotEquals(first.id(), other.id());
        assertTrue(other.id() > first.id());

        assertSame(stable, stableAgain);
        assertEquals(OresSymbol.Scope.STABLE, stable.scope());
        assertNotEquals(first, stable);
        assertTrue(first.processStable());
        assertTrue(stable.processStable());
        assertTrue(first.sendable());
        assertTrue(stable.sendable());
    }

    @Test
    void compilerLiteralAndDynamicProcessFactoryShareCanonicalIdentity() {
        OresSymbol dynamicFirst = OresSymbol.process("literal-shared-key");
        OresSymbol literalAfter = OresSymbol.processLiteral(
                "literal-shared-key", IsolatePolicy.developer());
        OresSymbol literalFirst = OresSymbol.processLiteral(
                "literal-first-key", IsolatePolicy.developer());
        OresSymbol dynamicAfter = OresSymbol.process("literal-first-key");

        assertSame(dynamicFirst, literalAfter);
        assertSame(literalFirst, dynamicAfter);
        assertEquals(dynamicFirst.id(), literalAfter.id());
        assertEquals(literalFirst.id(), dynamicAfter.id());
        assertTrue(OresSymbol.RESERVED_LITERAL_PROCESS_SYMBOLS > 0);
        assertTrue(OresSymbol.MAX_DYNAMIC_PROCESS_SYMBOLS < OresSymbol.MAX_PROCESS_SYMBOLS);
    }

    @Test
    void wireFormatUsesScopeAndKeyNeverProcessIds() {
        OresSymbol process = OresSymbol.process("wire-key");
        OresSymbol stable = OresSymbol.stable(UUID_KEY);

        OresSymbol.Wire processWire = process.toWire();
        OresSymbol.Wire stableWire = stable.toWire();

        assertEquals(OresSymbol.Scope.PROCESS, processWire.scope());
        assertEquals("wire-key", processWire.key());
        assertEquals(OresSymbol.Scope.STABLE, stableWire.scope());
        assertEquals(UUID_KEY, stableWire.key());
        assertSame(process, OresSymbol.fromWire(processWire, IsolatePolicy.developer()));
        assertSame(stable, OresSymbol.fromWire(stableWire, IsolatePolicy.developer()));

        assertThrows(SecurityException.class,
                () -> OresSymbol.fromWire(processWire, IsolatePolicy.strictFaas()));
        assertThrows(IllegalArgumentException.class,
                () -> new OresSymbol.Wire(OresSymbol.Scope.LOCAL, "forbidden"));
    }

    @Test
    void actorLocalSymbolsAreCanonicalOnlyInsideOwningActorAndAreNotSendable() throws Exception {
        AtomicReference<OresSymbol> observed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch producerDone = new CountDownLatch(1);
        CountDownLatch targetDelivered = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> target = runtime.spawn(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> targetDelivered.countDown());

            ActorRuntime.ActorRef<String> producer = runtime.spawn(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        try {
                            OresSymbol first = OresSymbol.local(
                                    "ephemeral", context.runtime(), context.policy());
                            OresSymbol again = OresSymbol.local(
                                    "ephemeral", context.runtime(), context.policy());
                            if (first != again) {
                                throw new AssertionError("actor-local Symbol was not canonical in its actor");
                            }
                            if (first.scope() != OresSymbol.Scope.LOCAL || first.sendable()) {
                                throw new AssertionError("actor-local Symbol has incorrect scope/sendability");
                            }
                            observed.set(first);
                            assertThrows(IllegalArgumentException.class, () -> target.send(first));
                            assertThrows(IllegalStateException.class, first::toWire);
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            producerDone.countDown();
                        }
                    });

            producer.send("go");
            assertTrue(producerDone.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(observed.get());
            assertEquals(1L, targetDelivered.getCount());
        }

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertThrows(IllegalStateException.class,
                    () -> OresSymbol.local("off-actor", runtime, IsolatePolicy.developer()));
        }
    }

    @Test
    void singletonModuleCanPinStableOrProcessBindingsButNotActorLocalSymbols() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define singleton module UniversalSymbols as
                  pub val Symbol request =
                      Symbol.stable("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                  val Symbol internal = Symbol.process("internal");
                end

                define module app as
                  pub fnc request_symbol() => Symbol {
                    return await UniversalSymbols.request;
                  }
                end
                """));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));

        IllegalArgumentException bareRead = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module UniversalSymbols as
                          pub val Symbol request =
                              new Symbol("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                        end

                        define module app as
                          pub fnc request_symbol() => Symbol {
                            return UniversalSymbols.request;
                          }
                        end
                        """)));
        assertTrue(bareRead.getMessage().contains("immediately awaited")
                || bareRead.getMessage().contains("assign"));

        IllegalArgumentException mutableExport = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module UniversalSymbols as
                          pub let Symbol request =
                              new Symbol("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                        end
                        """)));
        assertTrue(mutableExport.getMessage().contains("must use val or const"));

        IllegalArgumentException localState = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module BadSymbols as
                          pub val Symbol request = Symbol.local("request");
                        end
                        """)));
        assertTrue(localState.getMessage().contains("context-free")
                || localState.getMessage().contains("process"));
    }

    @Test
    void symbolBuiltinNamespaceCannotBeShadowed() {
        IllegalArgumentException moduleShadow = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module Symbol as
                          pub fnc x() => int { return 1; }
                        end
                        """)));
        assertTrue(moduleShadow.getMessage().contains("reserved"));

        IllegalArgumentException classShadow = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define class Symbol as
                          end
                        end
                        """)));
        assertTrue(classShadow.getMessage().contains("reserved"));

        IllegalArgumentException localShadow = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          pub fnc x() => int {
                            val int Symbol = 1;
                            return Symbol;
                          }
                        end
                        """)));
        assertTrue(localShadow.getMessage().contains("reserved"));
    }

    @Test
    void untrustedPoliciesCannotCreateDecodeOrReceiveSymbols() {
        Ast.Program literalProgram = TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc tag() => Symbol {
                    return :secret;
                  }
                end
                """));
        SecurityException literalDenied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(literalProgram, IsolatePolicy.strictFaas()));
        assertTrue(literalDenied.getMessage().contains("symbols")
                || literalDenied.getMessage().contains("Symbol"));

        Ast.Program factoryProgram = TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc tag() => Symbol {
                    return Symbol.process("secret");
                  }
                end
                """));
        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(factoryProgram, IsolatePolicy.strictFaas()));

        assertThrows(SecurityException.class,
                () -> OresSymbol.process("secret", IsolatePolicy.strictFaas()));
        assertThrows(SecurityException.class,
                () -> OresSymbol.stable(UUID_KEY, IsolatePolicy.strictFaas()));

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            CountDownLatch delivered = new CountDownLatch(1);
            ActorRuntime.ActorRef<Object> untrusted = runtime.spawn(
                    IsolatePolicy.strictFaas(),
                    () -> (message, context) -> delivered.countDown());

            SecurityException transportDenied = assertThrows(SecurityException.class,
                    () -> untrusted.send(List.of("safe", OresSymbol.process("secret"))));
            assertTrue(transportDenied.getMessage().contains("cannot cross"));
            assertEquals(1L, delivered.getCount());
        }
    }

    @Test
    void keysAndStableIdsAreStrictlyValidatedAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("   "));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("bad\nkey"));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.process("x".repeat(OresSymbol.MAX_KEY_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.stable("not-a-uuid"));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.stable("5d60d832-fc6a-4fa2-a551-1448c2df6bf"));
    }
}
