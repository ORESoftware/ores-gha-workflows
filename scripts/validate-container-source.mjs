#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const FROM_RE = /^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+(\S+))?\s*$/i;

function externalImageTag(image) {
  if (image.includes('@sha256:')) return { immutable: true, repository: image.split('@', 1)[0], tag: null };
  const lastSlash = image.lastIndexOf('/');
  const lastColon = image.lastIndexOf(':');
  if (lastColon <= lastSlash) return { immutable: false, repository: image, tag: null };
  return {
    immutable: false,
    repository: image.slice(0, lastColon),
    tag: image.slice(lastColon + 1),
  };
}

function repositoryBase(repository) {
  return repository.slice(repository.lastIndexOf('/') + 1).toLowerCase();
}

export function validateContainerSource(text) {
  const aliases = new Set();
  const externalImages = [];
  const errors = [];

  for (const [index, line] of text.split('\n').entries()) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const match = line.match(FROM_RE);
    if (!match) continue;

    const [, image, alias] = match;
    if (aliases.has(image.toLowerCase())) {
      if (alias) aliases.add(alias.toLowerCase());
      continue;
    }

    const parsed = externalImageTag(image);
    externalImages.push({ image, line: index + 1 });

    if (!parsed.immutable && !parsed.tag) {
      errors.push(`line ${index + 1}: external image ${image} has no explicit tag or digest`);
    } else if (parsed.tag?.toLowerCase() === 'latest') {
      errors.push(`line ${index + 1}: external image ${image} uses the mutable latest tag`);
    }

    if (repositoryBase(parsed.repository) === 'node' && !parsed.immutable) {
      const major = Number.parseInt(parsed.tag ?? '', 10);
      if (!Number.isInteger(major)) {
        errors.push(`line ${index + 1}: Node image ${image} must declare a numeric major or immutable digest`);
      } else if (major < 22) {
        errors.push(`line ${index + 1}: Node ${major} is below the supported container baseline (22+)`);
      }
    }

    if (alias) aliases.add(alias.toLowerCase());
  }

  if (externalImages.length === 0) errors.push('Dockerfile contains no external FROM image');
  return errors;
}

export function validateContainerSourceFile(directory = '.') {
  const dockerfile = resolve(directory, 'Dockerfile');
  const errors = validateContainerSource(readFileSync(dockerfile, 'utf8'));
  if (errors.length > 0) {
    throw new Error(errors.join('\n'));
  }
  return dockerfile;
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : '';
if (invokedPath === import.meta.url) {
  try {
    const dockerfile = validateContainerSourceFile(process.argv[2] ?? '.');
    console.log(`[container-policy] ok ${dockerfile}`);
  } catch (error) {
    console.error(`[container-policy] ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
