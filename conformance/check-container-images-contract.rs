#![forbid(unsafe_code)]
use std::{fs, process::ExitCode};

fn main() -> ExitCode {
    let workflow = fs::read_to_string(".github/workflows/container-images.yml").unwrap_or_default();
    let tsp = fs::read_to_string("contracts/typespec/container-images.tsp").unwrap_or_default();
    let schema = fs::read_to_string("contracts/json-schema/container-images.schema.json").unwrap_or_default();
    let mut errors = Vec::new();

    let inputs = [
        ("image-name", "image_name"), ("context", "context"), ("arm64", "arm64"),
        ("amd64", "amd64"), ("push", "push"), ("submodules", "submodules"),
        ("prepare_script", "prepare_script"), ("gcp-region", "gcp_region"),
        ("gcp-project", "gcp_project"), ("dockerhub-namespace", "dockerhub_namespace"),
        ("build-args", "build_args"),
    ];
    for (wire, tsp_name) in inputs {
        if !workflow.contains(&format!("      {wire}:")) || !schema.contains(&format!("\"{wire}\"")) || !tsp.contains(tsp_name) {
            errors.push(format!("workflow input authority drift: {wire}"));
        }
    }

    for secret in ["DOCKERHUB_USERNAME", "DOCKERHUB_TOKEN", "GCP_WORKLOAD_IDENTITY_PROVIDER", "GCP_SERVICE_ACCOUNT", "BUILD_SECRET_GITHUB_TOKEN"] {
        if !workflow.contains(&format!("      {secret}:")) || !schema.contains(&format!("\"{secret}\"")) {
            errors.push(format!("workflow secret authority drift: {secret}"));
        }
    }
    for output in ["arm64-digest", "amd64-digest", "manifest"] {
        if !workflow.contains(&format!("      {output}:")) || !schema.contains(&format!("\"{output}\"")) {
            errors.push(format!("workflow output authority drift: {output}"));
        }
    }

    if !workflow.contains("      push:\n        type: boolean\n        required: false\n        default: false") {
        errors.push("publication push must remain opt-in by default".into());
    }
    if !workflow.contains("google-github-actions/auth@") || !workflow.contains("workload_identity_provider:") {
        errors.push("GCP publication must remain Workload Identity based".into());
    }
    if workflow.contains("service_account_key") || workflow.contains("credentials_json") {
        errors.push("long-lived GCP service-account key material is forbidden".into());
    }
    if !schema.contains("https://json-schema.org/draft/2020-12/schema") {
        errors.push("workflow JSON Schema must remain Draft 2020-12".into());
    }

    for line in workflow.lines().map(str::trim).filter(|line| line.starts_with("uses:")) {
        let Some(reference) = line.split('@').nth(1) else {
            errors.push(format!("action reference has no immutable ref: {line}"));
            continue;
        };
        let sha = reference.split_whitespace().next().unwrap_or("");
        if sha.len() != 40 || !sha.bytes().all(|b| b.is_ascii_hexdigit()) {
            errors.push(format!("external Action is not pinned by full commit SHA: {line}"));
        }
    }

    if errors.is_empty() { ExitCode::SUCCESS } else {
        for error in errors { eprintln!("conformance error: {error}"); }
        ExitCode::FAILURE
    }
}
