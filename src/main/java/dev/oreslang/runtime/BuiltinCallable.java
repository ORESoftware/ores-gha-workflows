package dev.oreslang.runtime;

import java.util.List;

/**
 * Host-implemented callable exposed to Oreslang without granting guest code
 * reflective or general host access.
 */
@FunctionalInterface
public interface BuiltinCallable {
    Object call(List<Object> args);
}
