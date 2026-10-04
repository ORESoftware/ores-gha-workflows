package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class ContextualLambdaTest {
    @Test
    void declaredFunctionReturnTypeProvidesLambdaParameterContext() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) -> int;

                fnc make_adder(int base) => IntFn {
                  return |value| -> {
                    return base + value;
                  };
                }
                """)));
    }

    @Test
    void typedBindingProvidesLambdaParameterContext() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) -> int;

                pub routine main() => void {
                  val IntFn increment = |value| -> {
                    return value + 1;
                  };
                }
                """)));
    }

    @Test
    void functionArgumentProvidesLambdaParameterContext() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) -> int;

                fnc apply(IntFn op, int value) => int {
                  return op(value);
                }

                pub routine main() => void {
                  val answer = apply(|value| -> {
                    return value + 1;
                  }, 41);
                  stdio.println(answer);
                }
                """)));
    }

    @Test
    void assignmentTargetProvidesLambdaParameterContext() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) -> int;

                pub routine main() => void {
                  let IntFn op = |value| -> {
                    return value + 1;
                  };

                  op = |value| -> {
                    return value + 2;
                  };
                }
                """)));
    }

    @Test
    void ternaryReturnPropagatesFunctionContextIntoBothLambdas() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) -> int;

                fnc choose(bool plus) => IntFn {
                  return plus
                    ? |value| -> { return value + 1; }
                    : |value| -> { return value - 1; };
                }
                """)));
    }

    @Test
    void typedModuleAndClassFieldsProvideLambdaContext() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFn = typeof fnc(int value) -> int;

                val IntFn module_increment = |value| -> {
                  return value + 1;
                };

                define class Ops
                  val IntFn increment = |value| -> {
                    return value + 1;
                  };
                end
                """)));
    }
}
