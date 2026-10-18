import '@angular/compiler';
import { vi } from 'vitest';
import { HOOK_TIMEOUT_MS, TEST_TIMEOUT_MS } from './test-timeouts';

// Raise the per-test and per-hook budgets above Vitest's load-sensitive defaults.
// The numbers and the measurements behind them live in ./test-timeouts.ts, and the
// guard that keeps them there is ./test-timeouts.spec.ts.
vi.setConfig({ testTimeout: TEST_TIMEOUT_MS, hookTimeout: HOOK_TIMEOUT_MS });

// This module deliberately does NOT create the TestBed environment or load zone.js.
//
// `@angular/build:unit-test` generates its own `initTestEnvironment` entry
// (`[BrowserTestingModule, TestModule]` + `platformBrowserTesting()`, with
// `errorOnUnknownElements`/`errorOnUnknownProperties` on) and guards it with
// `Symbol.for('@angular/cli/testbed-setup')`. Keeping the hand-rolled
// `getTestBed().initTestEnvironment(BrowserDynamicTestingModule,
// platformBrowserDynamicTesting())` that used to live here made every one of the
// 179 spec files die on load under @angular/build 21 with
// `NG0400: A platform with a different configuration has been created. Please
// destroy it first.`, raised from this file's own init call.
//
// zone.js goes with it. The builder injects `provideZoneChangeDetection()` only
// when the build target's `polyfills` contain `zone.js`, and `angular.json` declares
// no `polyfills` at all, so leaving the zone.js imports in place was what kept the
// suite on the zone-based path while the application itself runs zoneless
// (`provideZonelessChangeDetection()` in `src/app/app.config.ts`). Dropping them
// makes the tests exercise what production runs.

// Mock window.matchMedia for jsdom (not available by default)
if (!window.matchMedia) {
  const noop = (): void => undefined;
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: noop,
      removeListener: noop,
      addEventListener: noop,
      removeEventListener: noop,
      dispatchEvent: () => false,
    }),
  });
}
