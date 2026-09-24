import '@angular/compiler';
import 'zone.js';
import 'zone.js/testing';
import 'zone.js/plugins/vitest-patch';
import { vi } from 'vitest';
import { getTestBed } from '@angular/core/testing';
import {
  BrowserDynamicTestingModule,
  platformBrowserDynamicTesting,
} from '@angular/platform-browser-dynamic/testing';
import { HOOK_TIMEOUT_MS, TEST_TIMEOUT_MS } from './test-timeouts';

// Raise the per-test and per-hook budgets above Vitest's load-sensitive defaults.
// The numbers and the measurements behind them live in ./test-timeouts.ts, and the
// guard that keeps them there is ./test-timeouts.spec.ts.
vi.setConfig({ testTimeout: TEST_TIMEOUT_MS, hookTimeout: HOOK_TIMEOUT_MS });

getTestBed().initTestEnvironment(BrowserDynamicTestingModule, platformBrowserDynamicTesting(), {
  teardown: { destroyAfterEach: true },
});

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
