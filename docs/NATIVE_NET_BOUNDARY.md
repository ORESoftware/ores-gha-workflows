# Oreslang native networking boundary

The networking architecture is:

```
Oreslang net/http stdlib (.ores)
        |
privileged native_net builtin ABI
        |
thin Java JNI declaration/binding layer (hosted Truffle build only)
        |
liboresnet / OS networking primitives
```

## Ownership

Oreslang owns protocol and API semantics: URI handling, HTTP serialization,
status/header parsing, framing, chunk decoding, redirects, authentication
policy, connection-pool policy, and public Java-shaped compatibility APIs.

Native code owns operating-system primitives: DNS, socket creation,
connect/bind/listen/accept, read/write, poll, socket options, and eventually
TLS primitives.

Java may marshal Truffle guest values into the privileged native ABI. Java must
not implement networking or HTTP semantics and must not delegate to
`java.net`, `java.net.http`, NIO sockets, or Java HTTP/TLS clients.

## Migration gate

The migration is complete only when:

1. `OresNet.java` contains no HTTP parser/serializer/redirect/URI engine.
2. Public `net` and `net.http` modules resolve to Oreslang stdlib modules.
3. The stdlib uses only the capability-gated `native_net` primitive namespace.
4. Native primitives are tested independently and Oreslang protocol behavior is
   tested through guest source.
5. strict/untrusted isolates receive NETWORK only through explicit capability
   admission; they never receive unrestricted FFI/NATIVE merely to use HTTP.


## Socket identity and actor handoff

A numeric OS file descriptor is never a guest capability. The fd remains inside
the native/JNI implementation and is wrapped immediately in an opaque
`NativeSocketHandle` carrying a unique generation and liveness gate.

This matters because fd numbers are process-global and reusable. A stale integer
must never regain authority merely because the kernel later assigns the same
number to a different socket.

For actor handoff, Oreslang transfers **authority**, not the integer fd:

1. the supervisor/acceptor owns the opaque transport,
2. the runtime selects an existing memory-isolated actor,
3. an ownership write barrier waits for in-flight I/O,
4. the actor becomes the sole controlling owner,
5. request/response bytes move directly between the transport and actor-owned
   memory,
6. actor termination revokes authority before memory reclamation and close.

The raw fd should not be copied into isolated actor memory. Within one OS
process the descriptor table is shared anyway; the secure primitive is an
owner-checked opaque capability. If Oreslang later supports actors in separate
OS processes, descriptor transfer must use the platform's real handle-passing
mechanism (for example Unix `SCM_RIGHTS`) rather than serializing fd integers.

For HTTP/2 and HTTP/3, connection ownership stays with the multiplexing runtime
and actor ownership is granted per request/response stream.


## Bounded actor I/O

A Java carrier-thread interrupt is not sufficient to preempt a thread blocked
inside `recv(2)` or `send(2)`. Before direct handoff to an isolated actor,
the native transport must therefore install positive kernel-enforced receive
and send timeouts. `NativeSocketBridge.setActorIoTimeout` sets both
`SO_RCVTIMEO` and `SO_SNDTIMEO` and rejects zero/infinite timeouts.

The final actor/native adapter should choose a timeout no larger than the
runtime's current actor-turn quantum (and no larger than the remaining
untrusted-actor lifetime). Timeout expiry returns control to the runtime so fuel,
deadline, cancellation, and ownership checks can run again.
