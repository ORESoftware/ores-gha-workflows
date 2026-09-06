import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const reusable = readFileSync(
  new URL('../.github/workflows/container-images.yml', import.meta.url),
  'utf8',
);
const template = readFileSync(new URL('../templates/images.yml', import.meta.url), 'utf8');

function actionReferences(text) {
  return [...text.matchAll(/^\s*-?\s*uses:\s*([^\s#]+)(?:\s+#.*)?$/gm)].map((match) => match[1]);
}

function runBlocks(text) {
  const lines = text.split('\n');
  const blocks = [];
  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(/^(\s*)run:\s*[>|]\s*$/);
    if (!match) continue;
    const baseIndent = match[1].length;
    const block = [];
    for (index += 1; index < lines.length; index += 1) {
      const line = lines[index];
      if (line.trim() && line.match(/^\s*/)[0].length <= baseIndent) {
        index -= 1;
        break;
      }
      block.push(line);
    }
    blocks.push(block.join('\n'));
  }
  return blocks;
}

test('every action and reusable workflow reference is immutable', () => {
  const references = [...actionReferences(reusable), ...actionReferences(template)];
  assert.ok(references.length >= 10, references);
  for (const reference of references) {
    const separator = reference.lastIndexOf('@');
    assert.notEqual(separator, -1, reference);
    assert.match(reference.slice(separator + 1), /^[a-f0-9]{40}$/, reference);
  }
});

test('shell source never interpolates workflow_call inputs directly', () => {
  for (const block of runBlocks(reusable)) {
    assert.doesNotMatch(block, /\$\{\{\s*inputs\./, block);
  }
});

test('publication defaults closed and PR validation receives no publication secrets', () => {
  assert.match(reusable, /push:\n\s+type: boolean\n\s+required: false\n\s+default: false/);
  assert.match(template, /verify:[\s\S]*?push: false/);
  assert.doesNotMatch(template.match(/verify:[\s\S]*?\n\s{2}publish:/)?.[0] ?? '', /secrets:/);
  assert.match(template, /publish:[\s\S]*?push: true[\s\S]*?BUILD_SECRET_GITHUB_TOKEN/);
});

test('caller template covers pull requests and requires explicit repository metadata', () => {
  assert.match(template, /pull_request:/);
  assert.match(template, /branches: \["__DEFAULT_BRANCH__"\]/);
  assert.match(template, /image-name: "__ORG__\/__IMAGE__"/);
  assert.match(template, /gcp-project: "__GCP_PROJECT__"/);
  assert.doesNotMatch(template, /raw\.githubusercontent\.com/);
  assert.doesNotMatch(template, /@(main|master|v\d+)\b/);
});

test('reusable workflow uses digest artifacts instead of lossy matrix job outputs', () => {
  assert.match(reusable, /actions\/upload-artifact@[a-f0-9]{40}/);
  assert.match(reusable, /actions\/download-artifact@[a-f0-9]{40}/);
  assert.match(reusable, /image-digest-\$\{\{ matrix\.arch \}\}/);
  assert.match(reusable, /\^sha256:\[a-f0-9\]\{64\}\$/);
  assert.doesNotMatch(reusable, /needs\.build\.outputs\.(arm64|amd64)-digest/);
});

test('policy downloads are revision- and checksum-bound', () => {
  assert.match(reusable, /POLICY_REVISION: [a-f0-9]{40}/);
  assert.match(reusable, /RENDERER_SHA256: [a-f0-9]{64}/);
  assert.match(reusable, /VALIDATOR_SHA256: [a-f0-9]{64}/);
  assert.match(reusable, /sha256sum --check --strict/);
  assert.doesNotMatch(reusable, /raw\.githubusercontent\.com\/[^\n]+\/(main|master)\//);
});
