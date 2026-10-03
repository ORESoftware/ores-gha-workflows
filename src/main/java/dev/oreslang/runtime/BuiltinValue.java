package dev.oreslang.runtime;

/**
 * Explicit, deny-by-default member surface for host/runtime values exposed to
 * Oreslang. Implementations decide exactly which members guest code can see.
 */
public interface BuiltinValue {
    Object member(String name);
}
