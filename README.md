# ores-gha-workflows

Reusable GitHub Actions workflows and the scripts they need, shared by every org.

## `container-images.yml` (workflow_call)

Builds `Dockerfile.arm64.dkf` (linux/arm64, on an arm runner — both k8s clusters are
aarch64) and `Dockerfile.x86-64.dkf` (linux/amd64, for Cloud Run) — falling back to
`Dockerfile` — pushes each by digest, then stitches multi-arch `:<branch>` and
`:sha-<12>` tags on **every** registry:

| registry | name | when |
|---|---|---|
| `ghcr.io/<org>/<repo>` | existing fleet default | always |
| `<region>-docker.pkg.dev/<gcp-project>/<org>/<repo>` | GCP Artifact Registry, `gcp-project` = the org's project (1:1) | `gcp-project` set |
| `docker.io/<namespace>/<org>-<repo>` | private Docker Hub namespace | `dockerhub-namespace` set |

Auth: GHCR via `GITHUB_TOKEN`; Docker Hub via org secrets `DOCKERHUB_USERNAME` /
`DOCKERHUB_TOKEN`; GCP via Workload Identity Federation (`GCP_WORKLOAD_IDENTITY_PROVIDER`,
`GCP_SERVICE_ACCOUNT`) — no JSON keys in GitHub. Copy `templates/images.yml` into a repo
and fill in `__ORG__/__IMAGE__`, `__GCP_PROJECT__`, `__DOCKERHUB_NS__`.

## `scripts/render-dkf.mjs`

`Dockerfile` stays the single authored file. The two `.dkf` files are **derived**:
each `FROM` gets `--platform=linux/<arch>` (stage aliases and `$BUILDPLATFORM`
cross-builders are left alone), `ARG TARGETARCH` is declared, and a header records the
source digest. `render-dkf.mjs <dir> --check` fails CI on drift, so nobody edits a `.dkf`
by hand. `npm test` covers the transform.
