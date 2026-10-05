# Native OS boundary

Oreslang runtime APIs should be implemented in this order of ownership:

```
Oreslang stdlib (.ores)
        |
small capability-gated primitive namespace
        |
thin Java Truffle/JNI marshaling boundary
        |
native OS library
        |
kernel APIs
```

Java is host bootstrap, not the implementation language for guest operating
system APIs.

## Networking

- HTTP/URI/framing/redirect/retry policy: Oreslang.
- Socket/DNS syscalls: `liboresnet`.
- Java: capability admission, opaque-handle marshaling, JNI declarations.
- Numeric socket fds never become guest values.

## Filesystem

- buffering, retry loops, text codecs, file abstractions, path policy: Oreslang.
- `open/read/write/fsync/unlink/close`: `liboresfs`.
- Java: UTF-8/byte marshaling, capability admission, JNI declarations.
- Numeric file fds never become guest values.

Compiler/launcher source discovery is a separate host concern and may continue
to use Java NIO while the compiler is hosted on GraalVM. That must not be
mistaken for the guest filesystem implementation.

## Hard rules

1. Do not add `java.net.Socket`, `ServerSocket`, `SocketChannel`,
   `java.net.http`, or JDK `HttpServer` as a guest transport fallback.
2. Do not implement guest file reads/writes with `java.nio.file.Files`,
   `FileInputStream`, `FileOutputStream`, or `RandomAccessFile`.
3. Raw OS descriptors remain native/JNI-private.
4. Opaque handles are move-only guest capabilities and cannot cross actor
   boundaries by value.
5. Capability checks occur both statically and at the primitive boundary.
6. Partial-write completion, protocol state machines, codecs, buffering, and
   other policy belong in Oreslang whenever the language can express them.
7. Native errors should eventually become structured Oreslang Result/error
   values rather than Java exception semantics.
