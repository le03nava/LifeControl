import { test as base, type Page } from '@playwright/test';
import { installApiMock, type ApiMockOptions } from '../mocks/api';
import { installKeycloakMock } from '../mocks/keycloak';

type AppFixtures = {
  page: Page;
  clientRoles: string[];
  username: string;
  startUnauthenticated: boolean;
  api: ApiMockOptions;
};

/**
 * `app` test instance that boots every test with mocked Keycloak and a mocked
 * API Gateway. Set `clientRoles` (client roles of `life-control-client`) to
 * shape the RBAC the app should observe.
 *
 * The mocks are installed by **overriding the `page` fixture**, not by adding a
 * `test.beforeEach` hook to this module. Playwright attaches a hook to the spec
 * file that is loading when the hook is declared, and this module is evaluated
 * only once per worker process; a `beforeEach` declared here therefore ends up
 * bound to whichever spec file loaded the module first. Every other file that
 * the same worker runs afterwards executes with no mocks at all, hits the real
 * Keycloak/API Gateway, and its assertions fail on UI that never renders. A
 * fixture is resolved from each test's own fixture set, so it runs for every
 * test whatever the order in which spec files are loaded.
 */
export const app = base.extend<AppFixtures>({
  clientRoles: ['lc-admin'],
  username: 'e2e-admin',
  startUnauthenticated: false,
  api: {},

  page: async ({ page, clientRoles, username, startUnauthenticated, api }, use) => {
    await installKeycloakMock(page, { clientRoles, username, startUnauthenticated });
    await installApiMock(page, api);
    await use(page);
  },
});

export { expect } from '@playwright/test';
