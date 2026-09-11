# ores-gha-workflows — agent notes

This repository owns reusable GitHub Actions workflows and deterministic support tools consumed across the ORE fleet.

- Treat every `workflow_call` input, output, secret, permission, runner selection, and published template as a compatibility surface.
- External Actions and reusable workflows must be pinned to immutable commit SHAs. Policy scripts fetched over HTTPS must use an immutable revision and checksum verification.
- Pull-request validation must remain credential-free and non-publishing. Publication is opt-in and belongs only on explicitly authorized push/manual lanes with least privilege.
- `Dockerfile` is authored source; architecture-specific `*.dkf` files are deterministic derived witnesses. Never use derivation as a reason to retain unsupported base images.
- Prefer changing shared logic here and then updating consumers to an immutable reviewed revision instead of copying divergent shell logic into each repository.
- Merge semantically; never rebase, force-push, reset, stash, or weaken a gate to make CI green.

<!-- BEGIN ores-agents-pointer: managed by ORESoftware/my-ai; edit there, not here -->

## Canonical agent instructions

Before doing anything else in this repository, also read:

    .ores/agents/AGENTS.md

That path is a symlink to `~/codes/oresoftware/my-ai/AGENTS.md`, whose canonical copy is
<https://github.com/ORESoftware/my-ai/blob/main/AGENTS.md>.

The symlink is deliberately **not committed**. If `.ores/agents/AGENTS.md` is unavailable locally, read the canonical GitHub copy instead. Keep `.ores/` ignored.

<!-- END ores-agents-pointer -->
