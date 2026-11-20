/// <reference types="vitest/globals" />
/**
 * Pins the Angular 22 migration invariants that no behavioural test can see: the toolchain the
 * front is on, the APIs the upgrade removed that must not come back, and the toast's animation
 * contract after `@angular/animations` left the dependency graph.
 *
 * They are guards, not documentation: a dependency bump, a re-added import or a re-shaped toast
 * stylesheet fails here instead of silently reverting the migration. Companion to
 * `src/build-environment.spec.ts`, which pins the coverage path the same way.
 *
 * Spec: odd/tasks/angular-22-upgrade.md, T8/T9 (S1), T10/T15 (S2, S3); and
 * `odd/tasks/dependency-runtime-parity.md`, T1 (S2) and T2 (S1) — the pin policy and the Node line.
 */
const fs = process.getBuiltinModule('fs');
const path = process.getBuiltinModule('path');

const read = (relative: string): string =>
  fs.readFileSync(path.join(process.cwd(), relative), 'utf8') as string;

/** Comments may name the APIs this guard keeps out, so the assertions read the code only. */
const withoutComments = (text: string): string =>
  text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

const readJson = (relative: string): Record<string, never> => JSON.parse(read(relative));

function walk(directory: string): string[] {
  const entries = fs.readdirSync(directory, { withFileTypes: true }) as {
    name: string;
    isDirectory(): boolean;
  }[];
  return entries.flatMap((entry) =>
    entry.isDirectory()
      ? walk(path.join(directory, entry.name))
      : [path.join(directory, entry.name)],
  );
}

const sourceFiles = (): string[] =>
  walk(path.join(process.cwd(), 'src')).filter((file) => /\.(ts|html)$/.test(file));

describe('Angular 22 migration invariants', () => {
  it('keeps the toolchain pinned to v22 and the removed packages out', () => {
    const pkg = readJson('package.json') as unknown as {
      dependencies: Record<string, string>;
      devDependencies: Record<string, string>;
    };
    const all = { ...pkg.dependencies, ...pkg.devDependencies };

    // One policy for every `@angular/*` package, not two: `@angular/material` requires
    // `@angular/cdk` exactly `22.3.0` and `@angular/compiler-cli` requires `@angular/compiler`
    // exactly `22.3.0`, so a caret on any of them can reach a version its peers forbid — and a
    // caret is exactly what lets `npm install` move one of them alone (issue #895, S2). Reading
    // the keys instead of listing them is what makes a package added later inherit the policy.
    const angularPackages = Object.keys(all).filter((name) => name.startsWith('@angular/'));
    expect(angularPackages).not.toHaveLength(0);
    for (const name of angularPackages) {
      expect(all[name], name).toBe('22.3.0');
    }
    expect(all['typescript']).toBe('~6.0.3');
    expect(all['angular-eslint']).toBe('22.7.0');
    expect(all['typescript-eslint']).toMatch(/^\^8\.73\.0$/);
    expect(all['keycloak-angular']).toMatch(/^\^22\./);

    // Removed in v22: `@angular/animations` is deprecated and its only consumer here migrated,
    // and `platform-browser-dynamic` existed solely for the hand-rolled TestBed init.
    expect(all['@angular/animations']).toBeUndefined();
    expect(all['@angular/platform-browser-dynamic']).toBeUndefined();
  });

  it('types the app against the Node line it runs on', () => {
    // The defect this guards: the tree ran Node 24 through `.nvmrc`, the image and CI while
    // `@types/node` still typed the app against 20, so a Node 24-only API was a type error and a
    // signature that changed between the two majors compiled against the wrong one (issue #895,
    // S1). Three majors can drift apart here, so all three are read: the runtime the developer
    // uses, the runtime the image and CI install, and the types.
    const pkg = readJson('package.json') as unknown as {
      engines: { node: string };
      devDependencies: Record<string, string>;
    };

    const majorOf = (spec: string): string | undefined => /\d+/.exec(spec)?.[0];

    const nvmrc = read('.nvmrc').trim();
    const engines = majorOf(pkg.engines.node);
    const types = majorOf(pkg.devDependencies['@types/node'] ?? '');

    expect(engines, 'engines.node').toBeDefined();
    expect(nvmrc.split('.')[0]).toBe(engines);
    expect(types, '@types/node').toBe(engines);
  });

  it('resolves the path aliases without the deprecated baseUrl', () => {
    const tsconfig = readJson('tsconfig.json') as unknown as {
      compilerOptions: { baseUrl?: string; paths: Record<string, string[]> };
    };

    expect(tsconfig.compilerOptions.baseUrl).toBeUndefined();
    expect(tsconfig.compilerOptions.paths).toEqual({
      '@shared/*': ['./src/shared/*'],
      '@features/*': ['./src/features/*'],
      '@core/*': ['./src/core/*'],
      '@app/*': ['./src/app/*'],
    });
  });

  it('does not bring back the APIs v22 removed', () => {
    for (const file of sourceFiles()) {
      expect(withoutComments(read(path.relative(process.cwd(), file))), file).not.toMatch(
        /from ['"]@angular\/animations['"]/,
      );
    }

    const appConfig = withoutComments(read('src/app/app.config.ts'));
    expect(appConfig).not.toContain('withFetch');
    expect(appConfig).not.toContain('provideAnimationsAsync');
  });

  it('rests the toast on the final state the old trigger ended on, with the same timings', () => {
    const scss = read('src/shared/ui/notification-toast/notification-toast.scss');
    expect(scss).toMatch(/transform:\s*translateX\(0\)\s+translateY\(-50%\);/);
    expect(scss).toContain('@keyframes notification-enter');
    expect(scss).toContain('animation: notification-enter 300ms ease-out;');
    expect(scss).toContain('@keyframes notification-leave');
    expect(scss).toContain('animation: notification-leave 300ms ease-in;');

    const html = read('src/shared/ui/notification-toast/notification-toast.html');
    expect(html).toContain('animate.enter="notification-enter"');
    expect(html).toContain('animate.leave="notification-leave"');
  });

  it('keeps every spec enabled', () => {
    const specs = walk(path.join(process.cwd(), 'src')).filter((file) => file.endsWith('.spec.ts'));
    for (const file of specs) {
      expect(read(path.relative(process.cwd(), file)), file).not.toMatch(
        /\b(it|test|describe)\.(skip|only|todo)\b/,
      );
    }
  });
});
