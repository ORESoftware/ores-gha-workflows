import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdtempSync, writeFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const script = new URL('../scripts/render-dkf.mjs', import.meta.url).pathname;
const fixture = new URL('./fixture', import.meta.url).pathname;

test('render + check round-trip, builder with $BUILDPLATFORM untouched, alias FROM untouched', () => {
  const d = mkdtempSync(join(tmpdir(), 'dkf-'));
  cpSync(join(fixture, 'Dockerfile'), join(d, 'Dockerfile'));
  writeFileSync(join(d, 'Dockerfile'), readFileSync(join(d, 'Dockerfile'), 'utf8') + '\nFROM builder AS tests\nRUN cargo test\n');
  execFileSync('node', [script, d]);
  const arm = readFileSync(join(d, 'Dockerfile.arm64.dkf'), 'utf8');
  const amd = readFileSync(join(d, 'Dockerfile.x86-64.dkf'), 'utf8');
  assert.match(arm, /FROM --platform=\$BUILDPLATFORM rust:1\.89-bookworm AS builder/);
  assert.match(arm, /FROM --platform=linux\/arm64 debian:bookworm-slim AS runtime/);
  assert.match(amd, /FROM --platform=linux\/amd64 debian:bookworm-slim AS runtime/);
  assert.match(arm, /\nFROM builder AS tests\n/, 'stage alias FROM must not get a platform');
  assert.equal((arm.match(/ARG TARGETARCH/g) ?? []).length, 1);
  execFileSync('node', [script, d, '--check']);
  writeFileSync(join(d, 'Dockerfile'), '# changed\n' + readFileSync(join(d, 'Dockerfile'), 'utf8'));
  assert.throws(() => execFileSync('node', [script, d, '--check'], { stdio: 'pipe' }), 'drift must fail the check');
});
