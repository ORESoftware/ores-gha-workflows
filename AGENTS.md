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

It exists at a fixed path *inside* the repository because some agents cannot walk up past
the repository root, so machine-wide instructions one or more directories above are
invisible to them. This pointer plus that path make the same file reachable from a working
directory anywhere in the tree.

The symlink is deliberately **not committed**: it names an absolute path that is only valid
on a machine with `~/codes/oresoftware/my-ai` checked out, so committing it would produce a
broken link for everyone else and for CI. `.ores/` is git-ignored for that reason. If
`.ores/agents/AGENTS.md` is missing on your machine, create it with:

    mkdir -p .ores/agents
    ln -sfn "$HOME/codes/oresoftware/my-ai/AGENTS.md" .ores/agents/AGENTS.md

or run `~/codes/oresoftware/my-ai/scripts/link-repo-agents.sh` once to do it for every git
repository under `~/codes`, and `--check` to verify them.

A missing `.ores/agents/AGENTS.md` is a setup gap on the reader's machine, never a reason to
skip the canonical instructions: fetch them from the URL above instead.

<!-- END ores-agents-pointer -->
