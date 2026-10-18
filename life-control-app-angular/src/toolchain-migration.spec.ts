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
 * Spec: odd/tasks/angular-22-upgrade.md, T8/T9 (S1), T10/T15 (S2, S3).
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

    for (const name of [
      '@angular/common',
      '@angular/compiler',
      '@angular/compiler-cli',
      '@angular/core',
      '@angular/forms',
      '@angular/platform-browser',
      '@angular/router',
      '@angular/build',
      '@angular/cli',
    ]) {
      expect(all[name], name).toMatch(/^\^22\.2\.3$/);
    }
    expect(all['@angular/cdk']).toBe('22.2.3');
    expect(all['@angular/material']).toBe('22.2.3');
    expect(all['typescript']).toBe('~6.0.3');
    expect(all['angular-eslint']).toBe('22.6.0');
    expect(all['typescript-eslint']).toMatch(/^\^8\.71\.2$/);
    expect(all['keycloak-angular']).toMatch(/^\^22\./);

    // Removed in v22: `@angular/animations` is deprecated and its only consumer here migrated,
    // and `platform-browser-dynamic` existed solely for the hand-rolled TestBed init.
    expect(all['@angular/animations']).toBeUndefined();
    expect(all['@angular/platform-browser-dynamic']).toBeUndefined();
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
