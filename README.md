# ores-gha-workflows

Reusable GitHub Actions workflows and deterministic support tools shared by the ORE repository fleet.

## `container-images.yml` (`workflow_call`)

The reusable workflow validates `Dockerfile` plus its architecture-derived files, builds enabled architectures independently, transfers immutable digests through Actions artifacts, and optionally publishes a multi-architecture manifest.

Publication is **off by default**. The caller template has two separate jobs:

- pull requests receive read-only repository permission, no provider credentials, and `push: false`;
- protected-branch pushes and explicit manual dispatches receive the narrowly required package/OIDC permissions and `push: true`.

Every external Action and reusable-workflow reference is a full immutable commit SHA. Policy tools are downloaded from an exact repository revision and verified by SHA-256 before execution. Workflow-call inputs are passed through environment values rather than interpolated into shell source.

### Registries

| Registry | Name | Activation |
|---|---|---|
| `ghcr.io/<org>/<repo>` | fleet default | every authorized publication |
| `<region>-docker.pkg.dev/<gcp-project>/<org>/<repo>` | GCP Artifact Registry | only when `gcp-project` is explicitly configured |
| `docker.io/<namespace>/<org>-<repo>` | private Docker Hub namespace | only when `dockerhub-namespace` is explicitly configured |

GHCR uses the workflow token. Docker Hub requires `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`. GCP uses Workload Identity Federation through `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT`; JSON service-account keys do not belong in GitHub.

Copy `templates/images.yml` into a repository only after replacing all five placeholders:

- `__DEFAULT_BRANCH__`
- `__ORG__`
- `__IMAGE__`
- `__GCP_PROJECT__` (empty string is an explicit temporary skip)
- `__DOCKERHUB_NS__` (empty string is an explicit skip)

Do not replace the reusable-workflow SHA with `main`, a version tag, or another mutable ref.

## Source and derivation policy

`Dockerfile` remains the authored source. `scripts/render-dkf.mjs` derives `Dockerfile.arm64.dkf` and `Dockerfile.x86-64.dkf`; stage aliases and `$BUILDPLATFORM` builders remain architecture-neutral. A source digest in each header makes drift detectable.

`scripts/validate-container-source.mjs` rejects:

- external images without an explicit tag or immutable digest;
- the mutable `latest` tag;
- Node container majors below 22;
- Dockerfiles with no external base image.

These checks intentionally stop legacy images such as `node:10` before they are copied into ARM64/AMD64 variants and published under new tags. Modernize the source Dockerfile first; never treat architecture derivation as an excuse to preserve an unsupported runtime.

## Validation

Run:

```sh
npm test
```

The suite covers deterministic Dockerfile derivation, source-image policy, immutable Action references, pull-request permission separation, digest handoff, exact policy-tool downloads, and shell-injection boundaries. Repository CI also runs `actionlint` from a digest-pinned container.
