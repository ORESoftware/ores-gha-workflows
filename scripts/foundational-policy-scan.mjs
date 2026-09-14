import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const selfRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHA40 = /^[0-9a-f]{40}$/i;
const COMMON_MANIFESTS = new Set([
  'package.json', 'package-lock.json', 'npm-shrinkwrap.json', 'pnpm-lock.yaml', 'yarn.lock',
  'Cargo.toml', 'Cargo.lock', 'go.mod', 'go.sum', 'pubspec.yaml', 'pubspec.lock',
  'gleam.toml', 'mix.exs', 'rebar.config', 'pom.xml', 'build.gradle', 'build.gradle.kts',
  'Package.swift', 'flake.nix', 'flake.lock', '.gitmodules', '.zpkg.toml', '.cli-flags.toml',
]);
const SKIP_DIRS = new Set(['.git', 'node_modules', 'target', 'dist', 'build', '.dart_tool', '.venv', 'vendor']);

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}
function canonicalBytes(value) {
  return Buffer.from(`${JSON.stringify(stable(value))}\n`);
}
function digest(value) {
  return crypto.createHash('sha256').update(canonicalBytes(value)).digest('hex');
}
function validatePolicy(policy) {
  const errors = [];
  if (policy?.schema !== 'ores.foundational-policy-snapshot/v1') errors.push('schema mismatch');
  if (!SHA40.test(policy?.source?.commit ?? '')) errors.push('source commit must be a 40-hex SHA');
  if (!SHA40.test(policy?.source?.blob_sha ?? '')) errors.push('source blob SHA must be 40 hex');
  if (!Array.isArray(policy?.repositories) || policy.repositories.length !== 30) errors.push('expected exactly 30 foundational repositories');
  const names = (policy?.repositories ?? []).map((item) => item.repository);
  if (new Set(names).size !== names.length) errors.push('duplicate foundational repository');
  for (const item of policy?.repositories ?? []) {
    if (!['active', 'compatibility', 'legacy'].includes(item.status)) errors.push(`invalid status for ${item.repository}`);
  }
  for (const item of policy?.compatibility_remotes ?? []) {
    if (!item.repository || !item.canonical_repository) errors.push('malformed compatibility redirect');
  }
  for (const item of policy?.legacy_repositories ?? []) {
    if (!item.repository) errors.push('malformed legacy repository entry');
  }
  return errors;
}
function classifyText(text, policy, source = '<memory>') {
  const findings = [];
  const controlled = [
    ...(policy.compatibility_remotes ?? []).map((item) => ({ ...item, kind: 'compatibility' })),
    ...(policy.legacy_repositories ?? []).map((item) => ({ ...item, kind: 'legacy' })),
  ];
  for (const item of controlled) {
    const escaped = item.repository.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(`${escaped}(?:\\.git)?(?:(?:@|#|\\?ref=)([A-Za-z0-9._/-]+))?`, 'gi');
    for (const match of text.matchAll(re)) {
      const ref = match[1] ?? null;
      const immutable = Boolean(ref && SHA40.test(ref));
      findings.push({
        source,
        repository: item.repository,
        kind: item.kind,
        ref,
        classification: immutable && item.immutable_historical_allowed
          ? 'immutable_historical_reference'
          : 'forbidden_mutable_reference',
        canonical_repository: item.canonical_repository ?? null,
      });
    }
  }
  return findings;
}
function walk(root) {
  const files = [];
  function visit(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory() && SKIP_DIRS.has(entry.name)) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) visit(full);
      else files.push(full);
    }
  }
  visit(root);
  return files;
}
function defaultManifests(root) {
  return walk(root).filter((file) => {
    const rel = path.relative(root, file).split(path.sep).join('/');
    const base = path.basename(file);
    return COMMON_MANIFESTS.has(base) || /^\.github\/workflows\/.*\.ya?ml$/.test(rel);
  });
}
function parseArgs(argv) {
  const out = { root: '.', manifests: [], all: false, selfTest: false, report: null, expectedDigest: null, policy: null };
  for (let i = 2; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--root') out.root = argv[++i];
    else if (arg === '--manifest') out.manifests.push(argv[++i]);
    else if (arg === '--all') out.all = true;
    else if (arg === '--self-test') out.selfTest = true;
    else if (arg === '--report') out.report = argv[++i];
    else if (arg === '--expected-digest') out.expectedDigest = argv[++i];
    else if (arg === '--policy') out.policy = argv[++i];
    else throw new Error(`unknown argument: ${arg}`);
  }
  return out;
}
function selfTest() {
  const fixture = JSON.parse(fs.readFileSync(path.join(selfRoot, 'tests/foundational-policy-cases.json'), 'utf8'));
  const policy = JSON.parse(fs.readFileSync(path.join(selfRoot, 'policy/foundational-policy.json'), 'utf8'));
  const expected = fs.readFileSync(path.join(selfRoot, 'policy/foundational-policy.sha256'), 'utf8').trim().split(/\s+/)[0];
  const failures = [];
  if (validatePolicy(policy).length) failures.push({ name: 'baseline_policy', actual: validatePolicy(policy) });
  if (digest(policy) !== expected) failures.push({ name: 'baseline_digest', expected, actual: digest(policy) });
  for (const test of fixture.reference_cases) {
    const findings = classifyText(test.text, policy, test.name);
    const actual = findings.length ? findings[0].classification : 'clean';
    if (actual !== test.expected) failures.push({ name: test.name, expected: test.expected, actual });
  }
  for (const test of fixture.policy_cases) {
    if (test.mutation === 'delete_schema') {
      const mutated = structuredClone(policy); delete mutated.schema;
      const actual = validatePolicy(mutated).length ? 'invalid_policy' : 'valid_policy';
      if (actual !== test.expected) failures.push({ name: test.name, expected: test.expected, actual });
    } else if (test.mutation === 'wrong_digest') {
      const actual = digest(policy) === '0'.repeat(64) ? 'digest_ok' : 'digest_mismatch';
      if (actual !== test.expected) failures.push({ name: test.name, expected: test.expected, actual });
    }
  }
  if (failures.length) {
    console.error(JSON.stringify({ failures }, null, 2));
    return 1;
  }
  console.log(`foundational policy self-test: ok (${fixture.reference_cases.length + fixture.policy_cases.length} cases)`);
  return 0;
}

const args = parseArgs(process.argv);
if (args.selfTest) process.exit(selfTest());
const root = path.resolve(args.root);
const policyPath = path.resolve(args.policy ?? path.join(selfRoot, 'policy/foundational-policy.json'));
const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
const policyErrors = validatePolicy(policy);
const actualDigest = digest(policy);
const expectedDigest = args.expectedDigest ?? fs.readFileSync(path.join(path.dirname(policyPath), 'foundational-policy.sha256'), 'utf8').trim().split(/\s+/)[0];
if (!/^[0-9a-f]{64}$/i.test(expectedDigest)) policyErrors.push('expected policy digest must be 64-hex SHA-256');
if (actualDigest !== expectedDigest) policyErrors.push(`policy digest mismatch: expected ${expectedDigest}, got ${actualDigest}`);

let files;
if (args.manifests.length) files = args.manifests.map((item) => path.resolve(root, item));
else if (args.all) files = walk(root);
else files = defaultManifests(root);

const findings = [];
for (const file of files.sort()) {
  if (!file.startsWith(`${root}${path.sep}`) && file !== root) {
    policyErrors.push(`manifest escapes scan root: ${file}`);
    continue;
  }
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    policyErrors.push(`manifest not found: ${path.relative(root, file)}`);
    continue;
  }
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch { continue; }
  findings.push(...classifyText(text, policy, path.relative(root, file).split(path.sep).join('/')));
}
const forbidden = findings.filter((item) => item.classification === 'forbidden_mutable_reference');
const report = {
  schema: 'ores.foundational-policy-report/v1',
  policy_source: policy.source,
  policy_digest: actualDigest,
  scanned_files: files.length,
  findings: findings.sort((a, b) => `${a.source}:${a.repository}`.localeCompare(`${b.source}:${b.repository}`)),
  forbidden_count: forbidden.length,
  policy_errors: policyErrors.sort(),
};
const rendered = `${JSON.stringify(report, null, 2)}\n`;
if (args.report) fs.writeFileSync(args.report, rendered);
else process.stdout.write(rendered);
process.exit(policyErrors.length || forbidden.length ? 1 : 0);
