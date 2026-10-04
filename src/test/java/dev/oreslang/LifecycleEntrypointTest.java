package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LifecycleEntrypointTest {
    @Test
    void mainAndInitAreValidTopLevelLifecycleCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc init() => void {
                  return;
                }

                pub routine main() => void {
                  return;
                }
                """)));
    }

    @Test
    void mainAndInitAreValidModuleLifecycleCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc init() => void {
                    return;
                  }

                  pub routine main() => void {
                    return;
                  }
                end
                """)));
    }

    @Test
    void sourceFileMayDeclareOnlyOneMainAcrossRootAndModules() {
        IllegalArgumentException moduleConflict = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module first
                          pub routine main() => void {
                            return;
                          }
                        end

                        define module second
                          pub fnc main() => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(moduleConflict.getMessage().contains("at most one main lifecycle callable"));

        IllegalArgumentException rootAndModuleConflict = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main() => void {
                          return;
                        }

                        define module app
                          pub fnc main() => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(rootAndModuleConflict.getMessage().contains("at most one main lifecycle callable"));
    }

    @Test
    void separateModulesMayEachDeclareTheirOwnInitHook() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module first
                  fnc init() => void {
                    return;
                  }
                end

                define module second
                  routine init() => void {
                    return;
                  }
                end
                """)));
    }

    @Test
    void mainCannotBeActorAtTopLevelOrModuleScope() {
        assertLifecycleActorRejected("""
                pub actor routine main() => void {
                  return;
                }
                """, "main");

        assertLifecycleActorRejected("""
                define module app
                  actor fnc main() => void {
                    return;
                  }
                end
                """, "main");
    }

    @Test
    void lifecycleActorRestrictionCoversSharedAndIsolateModes() {
        assertLifecycleActorRejected("""
                pub actor isolate routine main() => void {
                  return;
                }
                """, "main");

        assertLifecycleActorRejected("""
                define module app
                  actor fnc init() => void {
                    return;
                  }
                end
                """, "init");
    }

    @Test
    void initCannotBeActorAtTopLevelOrModuleScope() {
        assertLifecycleActorRejected("""
                actor fnc init() => void {
                  return;
                }
                """, "init");

        assertLifecycleActorRejected("""
                define module app
                  actor isolate routine init() => void {
                    return;
                  }
                end
                """, "init");
    }

    @Test
    void lifecycleNamesCannotBeUsedByTopLevelOrModuleBindings() {
        assertReservedLifecycleName("""
                val main = 1;
                """, "main");

        assertReservedLifecycleName("""
                define module app
                  val init = 1;
                end
                """, "init");
    }

    @Test
    void lifecycleNamesCannotBeUsedByTopLevelOrModuleTypes() {
        assertReservedLifecycleName("""
                type main = int;
                """, "main");

        assertReservedLifecycleName("""
                define module app
                  define class init
                  end
                end
                """, "init");

        assertReservedLifecycleName("""
                define module app
                  define interface main
                  end
                end
                """, "main");
    }

    @Test
    void lifecycleFunctionsMayBeImportedByTheirReservedNames() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                import fnc {main, init} from "./entry.ores";

                pub routine launch() => void {
                  return;
                }
                """)));
    }

    @Test
    void lifecycleNamesCannotBeUsedAsWildcardOrNonFunctionImportBindings() {
        assertReservedLifecycleName("""
                import * as init from "./lifecycle.ores";
                pub routine launch() => void {
                  return;
                }
                """, "init");

        assertReservedLifecycleName("""
                import class {main} from "./types.ores";
                pub routine launch() => void {
                  return;
                }
                """, "main");
    }

    @Test
    void localsMayStillUseMainAndInit() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine launch() => void {
                  val main = 1;
                  let init = 2;
                  init = init + main;
                  return;
                }
                """)));
    }

    @Test
    void classMembersMayStillUseMainAndInit() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class LifecycleNames
                  val int main = 1;
                  let int init = 2;

                  main() => void {
                    return;
                  }

                  init() => void {
                    return;
                  }
                end
                """)));
    }

    private static void assertLifecycleActorRejected(String source, String name) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(name + " is a file/module lifecycle entrypoint"));
        assertTrue(error.getMessage().contains("cannot be declared actor"));
    }

    private static void assertReservedLifecycleName(String source, String name) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(
                error.getMessage().contains("'" + name + "' is reserved"),
                () -> "unexpected lifecycle-name error: " + error.getMessage());
    }
}
