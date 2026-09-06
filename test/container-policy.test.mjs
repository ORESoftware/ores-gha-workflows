import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validateContainerSource } from '../scripts/validate-container-source.mjs';

test('accepts a supported Node baseline and build-stage aliases', () => {
  const errors = validateContainerSource(`
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS builder
RUN npm --version
FROM builder AS tests
RUN npm test
FROM node:24-bookworm-slim AS runtime
`);
  assert.deepEqual(errors, []);
});

test('rejects the mutable latest tag', () => {
  assert.deepEqual(validateContainerSource('FROM node:latest\n'), [
    'line 1: external image node:latest uses the mutable latest tag',
    'line 1: Node image node:latest must declare a numeric major or immutable digest',
  ]);
});

test('rejects unsupported Node majors', () => {
  assert.deepEqual(validateContainerSource('FROM node:10\n'), [
    'line 1: Node 10 is below the supported container baseline (22+)',
  ]);
});

test('rejects untagged external images', () => {
  assert.deepEqual(validateContainerSource('FROM debian\n'), [
    'line 1: external image debian has no explicit tag or digest',
  ]);
});

test('accepts immutable digest references', () => {
  const digest = 'a'.repeat(64);
  assert.deepEqual(validateContainerSource(`FROM node@sha256:${digest}\n`), []);
});

test('requires at least one external image', () => {
  assert.deepEqual(validateContainerSource('# no image yet\n'), [
    'Dockerfile contains no external FROM image',
  ]);
});
