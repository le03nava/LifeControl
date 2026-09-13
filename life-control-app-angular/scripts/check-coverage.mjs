import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SUMMARY_PATH = resolve(ROOT, 'coverage/coverage-summary.json');

const THRESHOLDS = {
  statements: 80,
  branches: 60,
  functions: 75,
  lines: 80,
};

let summary;
try {
  summary = JSON.parse(readFileSync(SUMMARY_PATH, 'utf8'));
} catch {
  console.error(`[check-coverage] No se encontró ${SUMMARY_PATH}. Probá con: ng test --code-coverage`);
  process.exit(1);
}

const total = summary.total;
const failures = [];

for (const [metric, min] of Object.entries(THRESHOLDS)) {
  const pct = total[metric]?.pct;
  if (pct === undefined) {
    failures.push(`  - ${metric}: métrica ausente en el reporte`);
    continue;
  }
  const ok = pct >= min;
  console.log(
    `[check-coverage] ${metric.padEnd(10)} ${String(pct.toFixed(2)).padStart(6)}%  ${ok ? 'OK' : `BAJO EL MÍNIMO (${min}%)`}`,
  );
  if (!ok) failures.push(`  - ${metric}: ${pct.toFixed(2)}% < mínimo ${min}%`);
}

if (failures.length > 0) {
  console.error(`[check-coverage] Umbrales de cobertura NO alcanzados:\n${failures.join('\n')}`);
  process.exit(1);
}

console.log('[check-coverage] Cobertura dentro de los umbrales. OK');