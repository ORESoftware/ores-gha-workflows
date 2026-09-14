# Foundational repository policy workflow

`.github/workflows/foundational-policy.yml` is a read-only `workflow_call` gate for canonical-source and lifecycle policy.

The authoritative catalog remains `ORESoftware/.github`. This repository stores an immutable **policy snapshot/receipt**, not a second editable authority. `policy/foundational-policy.json` records the exact `.github` source commit/blob, and `policy/foundational-policy.sha256` records the canonical JSON SHA-256.

## Caller example

Pin both the reusable workflow and policy checkout to a reviewed full commit SHA. The digest must match that commit's policy snapshot:

```yaml
jobs:
  foundational-policy:
    uses: ORESoftware/ores-gha-workflows/.github/workflows/foundational-policy.yml@<40_HEX_REVIEWED_SHA>
    with:
      policy_ref: <40_HEX_REVIEWED_SHA>
      policy_sha256: ee55531dc64d3af0e952cb9e958a80ca3a491ea60f99fe9952cbd7b6adeae605
```

The default scan covers dependency/package manifests, `.gitmodules`, `.zpkg.toml`, `.cli-flags.toml`, and GitHub workflows. To restrict the scan to an explicit set, supply newline-separated `manifest_paths`.

## Semantics

- Active foundational repository references are not blocked by this lifecycle rule.
- Compatibility and legacy repository references must not be new mutable/unpinned source references.
- Exact 40-hex historical pins are retained as `immutable_historical_reference` evidence when the policy allows them.
- Canonical redirect targets are clean.
- Malformed policy snapshots and digest mismatches fail closed before reference findings are accepted.

The job emits compact `ores.foundational-policy-report/v1` JSON as the reusable workflow `report` output for `ores-gh-bots` or other aggregators. It does not request cloud/provider credentials and uses only read-only repository permissions.

## Policy refresh

A policy refresh must be a reviewed PR in this repository that records a new immutable `.github` source commit/blob and updates the canonical SHA-256. Do not point the reusable workflow at `.github/main` or another moving ref.
