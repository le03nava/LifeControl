import { test as base } from '@playwright/test';
import { installApiMock, type ApiMockOptions } from '../mocks/api';
import { installKeycloakMock } from '../mocks/keycloak';

type AppFixtures = {
  clientRoles: string[];
  username: string;
  startUnauthenticated: boolean;
  api: ApiMockOptions;
};

/**
 * `app` test instance that boots every test with mocked Keycloak and a mocked
 * API Gateway. Set `clientRoles` (client roles of `life-control-client`) to
 * shape the RBAC the app should observe.
 */
export const app = base.extend<AppFixtures>({
  clientRoles: ['lc-admin'],
  username: 'e2e-admin',
  startUnauthenticated: false,
  api: {},
});

app.beforeEach(async ({ page, clientRoles, username, startUnauthenticated, api }) => {
  await installKeycloakMock(page, { clientRoles, username, startUnauthenticated });
  await installApiMock(page, api);
});

export { expect } from '@playwright/test';
