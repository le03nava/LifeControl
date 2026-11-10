import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * The purchase-order surface never prints a currency the response did not state (spec S6 of
 * `odd/tasks/purchase-order-money.md`).
 *
 * The screen used to answer the document's total by summing `quantity × unitPrice` in the browser and rendering it with
 * a hardcoded currency code — a number the server never stated and no other client could reproduce. The fix is that every
 * money render of the feature goes through the shared `MoneyPipe` with the document's own currency, and this spec is the
 * guard over the whole feature rather than over the three templates that were edited: a fourth template that reintroduces
 * either the literal or the pipe's global default fails here.
 *
 * It reads the sources the same way `src/features/sales/sales-orders/sales-money-currency.spec.ts` does, which is the
 * sales twin of this rule.
 */
const PURCHASE_ORDERS_ROOT = path.resolve(process.cwd(), 'src/features/purchases/purchase-orders');

function filesUnder(dir: string, extension: string, skip: (name: string) => boolean): string[] {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...filesUnder(full, extension, skip));
    } else if (entry.name.endsWith(extension) && !skip(entry.name)) {
      files.push(full);
    }
  }
  return files;
}

/**
 * `| currency` answers with `DEFAULT_CURRENCY_CODE` (`app.config.ts` pins it to one code) when it is given no argument,
 * and with whatever the template typed when it is. Neither is the document's currency, and this feature may not guess it.
 */
const CURRENCY_PIPE = /\|\s*currency\b/g;

/**
 * The literal the screen was hardcoded with. Written as a backreference so this file does not match its own pattern.
 */
const CURRENCY_LITERAL = /(['"`])MXN\1/;

describe('the purchase-order surface states the server’s currency', () => {
  const templates = filesUnder(PURCHASE_ORDERS_ROOT, '.html', () => false);
  // Spec fixtures state a currency on purpose — that is how they prove a render obeys the document instead of a
  // literal — so the rule is enforced on production sources and templates.
  const sources = filesUnder(PURCHASE_ORDERS_ROOT, '.ts', (name) => name.endsWith('.spec.ts'));

  it('should find the sources it audits', () => {
    // A guard over an empty set passes for the wrong reason, which is the defect this assertion prevents.
    expect(templates.length).toBeGreaterThan(0);
    expect(sources.length).toBeGreaterThan(0);
    expect(templates.some((file) => file.endsWith('detail-table.html'))).toBe(true);
    expect(templates.some((file) => file.endsWith('purchase-order-edit.html'))).toBe(true);
  });

  it('should render every amount through the money pipe, never through the currency pipe', () => {
    const violations: string[] = [];
    for (const file of templates) {
      const source = fs.readFileSync(file, 'utf8');
      for (const match of source.matchAll(CURRENCY_PIPE)) {
        const line = source.slice(0, match.index).split('\n').length;
        violations.push(`${path.relative(process.cwd(), file)}:${line}`);
      }
    }

    expect(violations).toEqual([]);
  });

  it('should never name a currency literal', () => {
    const violations: string[] = [];
    for (const file of [...templates, ...sources]) {
      const source = fs.readFileSync(file, 'utf8');
      if (CURRENCY_LITERAL.test(source)) {
        violations.push(path.relative(process.cwd(), file));
      }
    }

    expect(violations).toEqual([]);
  });

  it('should sum the lines nowhere on the surface', () => {
    // The two derivations S6 removed, asserted by name across the feature: `orderTotal` summed the rows into the
    // document total and `lineItemsTotal` summed them under the detail table.
    const violations: string[] = [];
    for (const file of sources) {
      const source = fs.readFileSync(file, 'utf8');
      if (/\b(orderTotal|lineItemsTotal|rowSubtotal)\b/.test(source)) {
        violations.push(path.relative(process.cwd(), file));
      }
    }

    expect(violations).toEqual([]);
  });
});
