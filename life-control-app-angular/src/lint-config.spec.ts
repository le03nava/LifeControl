/// <reference types="vitest/globals" />
/**
 * Pins the invariant no lint run can see: every package `eslint.config.js` requires is a dependency
 * this project declares.
 *
 * The defect this guards: the config required `@eslint/js` and the manifest never named it. It
 * resolved anyway, because eslint 9 shipped `@eslint/js` as its own dependency and npm hoisted that
 * copy into `node_modules`, so `npm ci` installed it without anyone asking. eslint 10 dropped the
 * edge, and a config that had been green for its whole life turned into
 * `Error when running ESLint: Cannot find module '@eslint/js'` (issue #1006, PR #1006) — at the
 * first step of the job, before a single file was linted.
 *
 * A `require` that only resolves through hoisting is not a dependency this project has: it is a
 * dependency of whichever package happens to bring it, and it moves when that package's major
 * moves. Naming it here makes the manifest and the config answer the same question.
 *
 * Every helper lives inside the test on purpose. A spec with no import or export is a script whose
 * top-level declarations are global, so a top-level `read`/`readJson` here collides with the ones
 * `src/toolchain-migration.spec.ts` declares and the suite fails with `TS2451: Cannot redeclare
 * block-scoped variable` before either test runs (measured).
 *
 * Spec: odd/tasks/dependency-runtime-parity.md, S2 (the pin policy that keeps a toolchain pin from
 * drifting silently); the same guard culture as `src/toolchain-migration.spec.ts`.
 */
describe('lint config dependencies', () => {
  it('declares every package eslint.config.js requires', () => {
    const fs = process.getBuiltinModule('fs');
    const path = process.getBuiltinModule('path');

    const read = (relative: string): string =>
      fs.readFileSync(path.join(process.cwd(), relative), 'utf8') as string;

    const pkg = JSON.parse(read('package.json')) as unknown as {
      dependencies: Record<string, string>;
      devDependencies: Record<string, string>;
    };
    const declared = { ...pkg.dependencies, ...pkg.devDependencies };

    // Builtins and relative paths are not packages, so only bare specifiers are asked about. The
    // match reads `require(...)` and not `import ... from` because the config is CommonJS today;
    // an `import` added later is a second spelling of the same question and belongs in this regex
    // at the same time as the config learns it.
    const required = [...read('eslint.config.js').matchAll(/require\(\s*['"]([^'"]+)['"]\s*\)/g)]
      .map((match) => match[1])
      .filter((specifier) => !specifier.startsWith('node:') && !specifier.startsWith('.'));

    expect(required).not.toHaveLength(0);
    for (const specifier of required) {
      expect(declared[specifier], `${specifier} is required by eslint.config.js`).toBeDefined();
    }
  });
});
