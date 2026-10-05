package dev.oreslang.net;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;

/** Shared fail-closed admission for privileged raw socket primitives. */
final class NetworkAdmission {
    private NetworkAdmission() { }

    static void requireRawNetwork(OresContext context, String api) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, api);
        rejectAdversarial(
                context.isolatePolicy(),
                ActorRuntime.currentActorPolicy(),
                api);
    }

    static void rejectAdversarial(
            IsolatePolicy contextPolicy,
            IsolatePolicy actorPolicy,
            String api) {
        boolean adversarial = contextPolicy.adversarial()
                || (actorPolicy != null && actorPolicy.adversarial());
        if (adversarial) {
            throw new SecurityException(
                    "adversarial isolates cannot access raw sockets through "
                            + api + "; use the bounded stateless HTTP surface");
        }
    }
}
