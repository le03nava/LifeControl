/// <reference types="vitest/globals" />
/**
 * Guard for issue #895, S5: no live surface names an Angular version that `package.json` does not
 * resolve.
 *
 * The version is read from `@angular/core` instead of hardcoded, so an Angular upgrade does not have
 * to edit this file — and a document the upgrade forgot fails here instead of shipping. The bug this
 * exists for: the footer rendered "Angular 20 Best Practices" to the user and no test noticed, and
 * `life-control-app-angular/README.md` said "Angular Material 20" in a table whose sibling row had
 * already been corrected.
 *
 * Only live surfaces are read. `odd/tasks/**` is frozen history and is never scanned: a record of
 * what the tree used to be is evidence, and rewriting it would destroy that evidence.
 *
 * Comments are stripped from `.ts` files only. A source comment that says "Angular 20" as history
 * ("this changed in Angular 20") is accurate and must stay; the same sentence in a document or in a
 * template is a claim about what this app runs, which is what S5 is about.
 *
 * Angular only, deliberately: that is S5's scope. Node and TypeScript claims are not guarded here,
 * so a stale `TypeScript 5.0+` in a template is caught by review and not by this test.
 *
 * Naming: a spec file without an `import` or `export` is a script, so its top-level declarations live
 * in the global scope and collide with every other script in the same program (`TS2451`). That is why
 * the helpers below are named for this file and why `build-environment.spec.ts` and
 * `test-timeouts.spec.ts` call theirs `readProjectFile`, `readSetupSource` and `stripNonCode`.
 */
const claimFs = process.getBuiltinModule('fs');
const claimPath = process.getBuiltinModule('path');

const readClaimSource = (relative: string): string =>
  claimFs.readFileSync(claimPath.join(process.cwd(), relative), 'utf8') as string;

const stripComments = (text: string): string =>
  text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

function walkSources(directory: string): string[] {
  const entries = claimFs.readdirSync(claimPath.join(process.cwd(), directory), {
    withFileTypes: true,
  }) as { name: string; isDirectory(): boolean }[];
  return entries.flatMap((entry) => {
    const child = claimPath.join(directory, entry.name);
    return entry.isDirectory() ? walkSources(child) : [child];
  });
}

const claimPkg = JSON.parse(readClaimSource('package.json')) as unknown as {
  dependencies: Record<string, string>;
  devDependencies: Record<string, string>;
};

/**
 * `Angular 22`, `Angular 22.2.3`, `Angular Material 22`, `Angular CDK 22`.
 */
const CLAIM = /\bAngular (Material |CDK )?(\d+)(?:\.\d+)*/g;

/**
 * `Angular Material 3` is Material Design 3 — the theme generation — and says nothing about which
 * Angular this app runs; the same two words with a two-digit number are the package version, which
 * is the claim that went stale. Angular majors are two digits today and the theme generation is
 * one, so the digit count separates the two readings. Without this the guard fails on correct text
 * (`README.md`, `AGENTS.md`) and the next person deletes it instead of trusting it.
 */
const isVersionClaim = (qualifier: string | undefined, major: string): boolean =>
  qualifier === undefined || major.length > 1;

const liveSurfaces = (): string[] => [
  'README.md',
  'AGENTS.md',
  '../README.md',
  '../AGENTS.md',
  ...walkSources('src').filter((file) => /\.(ts|html)$/.test(file)),
];

const claimsIn = (file: string): { text: string; major: string }[] => {
  const text = readClaimSource(file);
  const surface = file.endsWith('.ts') ? stripComments(text) : text;
  return [...surface.matchAll(CLAIM)].flatMap((match) =>
    isVersionClaim(match[1], match[2]) ? [{ text: match[0], major: match[2] }] : [],
  );
};

describe('Angular version claims', () => {
  it('names only the major @angular/core resolves', () => {
    const declared =
      { ...claimPkg.dependencies, ...claimPkg.devDependencies }['@angular/core'] ?? '';
    const major = /\d+/.exec(declared)?.[0];
    expect(major, '@angular/core must be declared in package.json').toBeDefined();

    const offenders: string[] = [];
    for (const file of liveSurfaces()) {
      for (const claim of claimsIn(file)) {
        if (claim.major !== major) offenders.push(`${file}: ${claim.text}`);
      }
    }

    expect(offenders).toEqual([]);
  });
});
