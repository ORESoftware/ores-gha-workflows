#!/usr/bin/env node
// Derive Dockerfile.arm64.dkf and Dockerfile.x86-64.dkf from Dockerfile.
//   node render-dkf.mjs [dir] [--check]
// Transform (pure, reviewable):
//   - every `FROM [--platform=…] <image> [AS name]` gets `--platform=linux/<arch>` unless the image is
//     a build-stage alias (FROM <alias>) or uses $BUILDPLATFORM (cross-compiling builders keep it);
//   - a header records the source Dockerfile's sha256 so `--check` can detect drift;
//   - `ARG TARGETARCH` is declared after the first FROM if absent, so RUN steps can branch on it.
// Anything else (RUN, COPY, …) is untouched: the .dkf files are arch pins, not forks.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const args = process.argv.slice(2);
const dir = args.find((a) => !a.startsWith('--')) ?? '.';
const check = args.includes('--check');
const src = join(dir, 'Dockerfile');
if (!existsSync(src)) { console.error(`[render-dkf] no Dockerfile in ${dir}`); process.exit(2); }
const text = readFileSync(src, 'utf8');
const digest = createHash('sha256').update(text).digest('hex');

export function render(text, arch, digest) {
  const platform = arch === 'arm64' ? 'linux/arm64' : 'linux/amd64';
  const aliases = new Set();
  for (const m of text.matchAll(/^\s*FROM\s+(?:--platform=\S+\s+)?\S+\s+AS\s+(\S+)/gim)) aliases.add(m[1].toLowerCase());
  let firstFromSeen = false; let hasTargetArch = /^\s*ARG\s+TARGETARCH\b/im.test(text);
  const out = [];
  for (const line of text.split('\n')) {
    const m = line.match(/^(\s*)FROM(\s+)(?:--platform=(\S+)\s+)?(\S+)(.*)$/i);
    if (m) {
      const [, indent, , plat, image, rest] = m;
      const isAlias = aliases.has(image.toLowerCase());
      const keep = isAlias || (plat && /BUILDPLATFORM/i.test(plat));
      out.push(keep ? line : `${indent}FROM --platform=${platform} ${image}${rest}`);
      if (!firstFromSeen && !hasTargetArch) { out.push('ARG TARGETARCH'); hasTargetArch = true; }
      firstFromSeen = true;
      continue;
    }
    out.push(line);
  }
  return `# GENERATED from Dockerfile (sha256:${digest.slice(0, 16)}) by ores-gha-workflows/scripts/render-dkf.mjs — target ${platform}. Do not edit; edit Dockerfile and re-render.\n${out.join('\n')}`;
}

let drift = 0;
for (const [arch, name] of [['arm64', 'Dockerfile.arm64.dkf'], ['x86-64', 'Dockerfile.x86-64.dkf']]) {
  const want = render(text, arch, digest);
  const path = join(dir, name);
  if (check) {
    const have = existsSync(path) ? readFileSync(path, 'utf8') : '';
    if (have !== want) { drift++; console.error(`[render-dkf] DRIFT ${path}`); }
    else console.log(`[render-dkf] ok ${path}`);
  } else { writeFileSync(path, want); console.log(`[render-dkf] wrote ${path}`); }
}
process.exit(drift ? 1 : 0);
