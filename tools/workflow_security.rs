use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process;

const MAX_DEPTH: usize = 16;
const MAX_ENTRIES: usize = 4096;
const MAX_FILE_BYTES: u64 = 1_048_576;

#[derive(Debug, PartialEq, Eq)]
enum ScanError {
    Io,
    InvalidUtf8,
    Symlink(PathBuf),
    TooDeep(PathBuf),
    TooManyEntries,
    FileTooLarge(PathBuf),
}

fn is_hex(value: &str, width: usize) -> bool {
    if value.len() != width {
        return false;
    }

    return value
        .bytes()
        .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f' | b'A'..=b'F'));
}

fn immutable_reference(value: &str) -> bool {
    if value.starts_with("./") {
        return true;
    }

    if let Some(image) = value.strip_prefix("docker://") {
        let Some((name, digest)) = image.rsplit_once("@sha256:") else {
            return false;
        };

        if name.is_empty() {
            return false;
        }

        return is_hex(digest, 64);
    }

    let Some((action, revision)) = value.rsplit_once('@') else {
        return false;
    };

    if action.is_empty() {
        return false;
    }

    return is_hex(revision, 40);
}

fn uses_reference(line: &str) -> Option<String> {
    let mut value = line.trim_start();

    if let Some(rest) = value.strip_prefix('-') {
        value = rest.trim_start();
    }

    let rest = value.strip_prefix("uses:")?;
    let without_comment = rest.split('#').next().unwrap_or_default().trim();

    if without_comment.is_empty() {
        return Some(String::new());
    }

    let unquoted = if without_comment.len() >= 2
        && ((without_comment.starts_with('"') && without_comment.ends_with('"'))
            || (without_comment.starts_with('\'') && without_comment.ends_with('\'')))
    {
        &without_comment[1..without_comment.len() - 1]
    } else {
        without_comment
    };

    return Some(unquoted.trim().to_owned());
}

fn validate_text(path: &Path, text: &str, findings: &mut Vec<String>) {
    for (index, line) in text.lines().enumerate() {
        let Some(reference) = uses_reference(line) else {
            continue;
        };

        if immutable_reference(&reference) {
            continue;
        }

        findings.push(format!(
            "{}:{}: mutable or invalid action reference: {}",
            path.display(),
            index + 1,
            reference
        ));
    }
}

fn workflow_file(path: &Path) -> bool {
    return matches!(
        path.extension().and_then(|value| value.to_str()),
        Some("yml" | "yaml")
    );
}

fn scan_root(root: &Path, findings: &mut Vec<String>) -> Result<(), ScanError> {
    match fs::symlink_metadata(root) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() {
                return Err(ScanError::Symlink(root.to_path_buf()));
            }

            if !metadata.is_dir() {
                return Ok(());
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(());
        }
        Err(_) => {
            return Err(ScanError::Io);
        }
    }

    let mut pending = vec![(root.to_path_buf(), 0_usize)];
    let mut entries_seen = 0_usize;

    while let Some((directory, depth)) = pending.pop() {
        if depth > MAX_DEPTH {
            return Err(ScanError::TooDeep(directory));
        }

        let mut entries = fs::read_dir(&directory)
            .map_err(|_| ScanError::Io)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|_| ScanError::Io)?;

        entries.sort_by_key(|entry| entry.path());

        for entry in entries {
            entries_seen += 1;
            if entries_seen > MAX_ENTRIES {
                return Err(ScanError::TooManyEntries);
            }

            let path = entry.path();
            let metadata = fs::symlink_metadata(&path).map_err(|_| ScanError::Io)?;

            if metadata.file_type().is_symlink() {
                return Err(ScanError::Symlink(path));
            }

            if metadata.is_dir() {
                pending.push((path, depth + 1));
                continue;
            }

            if !metadata.is_file() || !workflow_file(&path) {
                continue;
            }

            if metadata.len() > MAX_FILE_BYTES {
                return Err(ScanError::FileTooLarge(path));
            }

            let bytes = fs::read(&path).map_err(|_| ScanError::Io)?;
            let text = String::from_utf8(bytes).map_err(|_| ScanError::InvalidUtf8)?;
            validate_text(&path, &text, findings);
        }
    }

    return Ok(());
}

fn roots_from_args() -> Vec<PathBuf> {
    let args = env::args_os().skip(1).map(PathBuf::from).collect::<Vec<_>>();

    if args.is_empty() {
        return vec![
            PathBuf::from(".github/workflows"),
            PathBuf::from(".github/actions"),
        ];
    }

    return args;
}

fn run() -> Result<(), ScanError> {
    let roots = roots_from_args();

    if roots.len() > 16 {
        return Err(ScanError::TooManyEntries);
    }

    let mut findings = Vec::new();

    for root in roots {
        scan_root(&root, &mut findings)?;
    }

    findings.sort();

    if !findings.is_empty() {
        for finding in findings {
            eprintln!("{finding}");
        }

        process::exit(1);
    }

    println!("workflow-security: PASS (remote actions pinned to immutable revisions)");

    return Ok(());
}

fn main() {
    if let Err(error) = run() {
        eprintln!("workflow-security: FAIL {error:?}");
        process::exit(2);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_commit_sha_is_accepted() {
        assert!(immutable_reference(
            "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
        ));
    }

    #[test]
    fn local_action_is_accepted() {
        assert!(immutable_reference("./actions/workflow-security"));
    }

    #[test]
    fn digest_pinned_docker_action_is_accepted() {
        assert!(immutable_reference(
            "docker://rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667"
        ));
    }

    #[test]
    fn mutable_github_references_are_rejected() {
        for value in [
            "actions/checkout@main",
            "actions/checkout@v7",
            "actions/checkout@3d3c42e5aac5",
            "actions/checkout",
        ] {
            assert!(!immutable_reference(value), "{value}");
        }
    }

    #[test]
    fn mutable_docker_references_are_rejected() {
        for value in [
            "docker://ubuntu:latest",
            "docker://rhysd/actionlint:v1",
            "docker://rhysd/actionlint",
        ] {
            assert!(!immutable_reference(value), "{value}");
        }
    }

    #[test]
    fn quoted_and_list_uses_values_are_parsed() {
        assert_eq!(
            uses_reference("      - uses: \"actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1\" # pin"),
            Some("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1".to_owned())
        );
        assert_eq!(
            uses_reference("    uses: './actions/local'"),
            Some("./actions/local".to_owned())
        );
    }

    #[test]
    fn unrelated_lines_are_ignored() {
        assert_eq!(uses_reference("run: echo uses: foo@bar"), None);
        assert_eq!(uses_reference("# uses: actions/checkout@main"), None);
    }

    #[test]
    fn invalid_reference_becomes_a_finding() {
        let mut findings = Vec::new();
        validate_text(
            Path::new("fixture.yml"),
            "steps:\n  - uses: actions/checkout@main\n",
            &mut findings,
        );

        assert_eq!(findings.len(), 1);
        assert!(findings[0].contains("fixture.yml:2"));
    }
}
