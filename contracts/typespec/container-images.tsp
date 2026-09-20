namespace Ores.GhaWorkflows;

model ContainerImagesInputs {
  image_name: string;
  context?: string;
  arm64?: boolean;
  amd64?: boolean;
  push?: boolean;
  submodules?: string;
  prepare_script?: string;
  gcp_region?: string;
  gcp_project?: string;
  dockerhub_namespace?: string;
  build_args?: string;
}

model ContainerImagesSecrets {
  dockerhub_username?: string;
  dockerhub_token?: string;
  gcp_workload_identity_provider?: string;
  gcp_service_account?: string;
  build_secret_github_token?: string;
}

model ContainerImagesOutputs {
  arm64_digest?: string;
  amd64_digest?: string;
  manifest?: string;
}

model PublicationPolicy {
  push_default: false;
  pull_request_publish: false;
  immutable_action_refs: true;
  gcp_auth: "workload-identity";
}
