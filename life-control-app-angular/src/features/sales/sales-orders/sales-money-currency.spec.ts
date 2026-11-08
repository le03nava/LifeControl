import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * The sales surface never prints a currency it was not given (ADR-0002 decision 1, spec S2).
 *
 * `provide(DEFAULT_CURRENCY_CODE, { useValue: 'MXN' })` in `app.config.ts` makes a bare `| currency` answer MXN
 * for any template that forgot its code, which is the silent-default failure class `odd/tasks/money-first-class.md`
 * decision `D-c` forbids. The token stays for the components T8 does not own, so the rule needs a guard rather
 * than a removal: this spec is that guard, and it reads the template sources the same way
 * `src/shared/constants/breakpoints.spec.ts` reads the SCSS source.
 */
const SALES_ROOT = path.resolve(process.cwd(), 'src/features/sales');

function htmlFilesUnder(dir: string): string[] {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...htmlFilesUnder(full));
    } else if (entry.name.endsWith('.html')) {
      files.push(full);
    }
  }
  return files;
}

/** `{{ amount | currency }}` answers with the global default; `{{ amount | currency: code }}` does not. */
const BARE_CURRENCY_PIPE = /\|\s*currency\s*(\}\}|\|)/g;

describe('the sales surface states its currency', () => {
  const templates = htmlFilesUnder(SALES_ROOT);

  it('should find the sales templates it audits', () => {
    // A guard over an empty set passes for the wrong reason, which is the defect this assertion prevents.
    expect(templates.length).toBeGreaterThan(0);
    expect(templates.some((file) => file.endsWith('sales-order-item-table.html'))).toBe(true);
  });

  it('should never use the currency pipe without a currency code', () => {
    const violations: string[] = [];
    for (const file of templates) {
      const source = fs.readFileSync(file, 'utf8');
      for (const match of source.matchAll(BARE_CURRENCY_PIPE)) {
        const line = source.slice(0, match.index).split('\n').length;
        violations.push(`${path.relative(process.cwd(), file)}:${line}`);
      }
    }

    expect(violations).toEqual([]);
  });

  it('should never name a currency literal', () => {
    const violations: string[] = [];
    for (const file of templates) {
      const source = fs.readFileSync(file, 'utf8');
      if (/['"]MXN['"]/.test(source)) {
        violations.push(path.relative(process.cwd(), file));
      }
    }

    expect(violations).toEqual([]);
  });
});
