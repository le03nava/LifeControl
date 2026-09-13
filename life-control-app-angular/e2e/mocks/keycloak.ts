import type { Page, Route } from '@playwright/test';
import { buildTokenSet } from '../support/tokens';

const KEYCLOAK_BASE = process.env.E2E_KEYCLOAK_URL || 'http://localhost:8181';
const REALM = 'life-control-realm';
const PROTOCOL_PATH = `/realms/${REALM}/protocol/openid-connect`;
const ISSUER = `${KEYCLOAK_BASE}/realms/${REALM}`;
const DEFAULT_REDIRECT = 'http://localhost:4200/';

export interface KeycloakMockOptions {
  clientRoles?: string[];
  username?: string;
  startUnauthenticated?: boolean;
}

const htmlPage = (script: string): string =>
  `<!doctype html><html><body><script>${script}</script></body></html>`;

const CORS_HEADERS = (origin: string | undefined): Record<string, string> =>
  origin
    ? {
        'access-control-allow-origin': origin,
        'access-control-allow-credentials': 'true',
        'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS',
        'access-control-allow-headers': 'authorization,content-type',
        vary: 'Origin',
      }
    : {};

/**
 * Mocks the Keycloak authorization server (keycloak-js v26 flow) at the network
 * level so the E2E suite runs without a real Keycloak instance:
 *
 *  1. `3p-cookies/step1.html`  → reports third-party cookies as supported.
 *  2. `login-status-iframe.html` → reports a session change on the first poll,
 *     forcing the silent SSO flow; every later poll reports "unchanged" so the
 *     scheduled session checks keep the token. In `startUnauthenticated` mode it
 *     always reports "unchanged", leaving the app unauthenticated until the user
 *     clicks the Login button.
 *  3. `/auth` → redirects to the requested `redirect_uri` with a fabricated
 *     authorization `code` that transports the original `nonce`.
 *  4. `/token` → returns fabricated JWTs whose `resource_access.life-control-client.roles`
 *     drive the app RBAC (header menus + route guards).
 *  5. `/userinfo` and `/logout` → minimal stubs.
 */
export async function installKeycloakMock(
  page: Page,
  options: KeycloakMockOptions = {},
): Promise<void> {
  const clientRoles = [...(options.clientRoles ?? ['lc-admin'])];
  const username = options.username ?? 'e2e-admin';
  const startUnauthenticated = options.startUnauthenticated ?? false;

  const sessionScript = startUnauthenticated
    ? "window.addEventListener('message', () => { parent.postMessage('unchanged', '*'); });"
    : "let checks = 0; window.addEventListener('message', () => { parent.postMessage(checks++ === 0 ? 'changed' : 'unchanged', '*'); });";

  await page.route(`${KEYCLOAK_BASE}/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const cors = CORS_HEADERS(request.headers()['origin']);

    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors, body: '' });
      return;
    }

    switch (path) {
      case `${PROTOCOL_PATH}/3p-cookies/step1.html`:
        await route.fulfill({
          status: 200,
          contentType: 'text/html',
          body: htmlPage("parent.postMessage('supported', '*');"),
        });
        return;

      case `${PROTOCOL_PATH}/login-status-iframe.html`:
        await route.fulfill({
          status: 200,
          contentType: 'text/html',
          body: htmlPage(sessionScript),
        });
        return;

      case `${PROTOCOL_PATH}/auth`:
        await handleAuthorize(route);
        return;

      case `${PROTOCOL_PATH}/token`:
        await handleToken(route, clientRoles, username, cors);
        return;

      case `${PROTOCOL_PATH}/userinfo`:
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: cors,
          body: JSON.stringify({
            sub: username,
            preferred_username: username,
            name: 'E2E User',
            email: `${username}@example.com`,
            email_verified: true,
          }),
        });
        return;

      case `${PROTOCOL_PATH}/logout`:
        await handleLogout(route);
        return;

      default:
        await route.fulfill({
          status: 404,
          contentType: 'text/plain',
          body: `Unhandled mock path: ${path}`,
        });
    }
  });
}

async function handleAuthorize(route: Route): Promise<void> {
  const url = new URL(route.request().url());
  const redirectUri = url.searchParams.get('redirect_uri') ?? DEFAULT_REDIRECT;
  const state = url.searchParams.get('state') ?? '';
  const nonce = url.searchParams.get('nonce') ?? '';

  const target = new URL(redirectUri);
  target.hash = new URLSearchParams({
    code: Buffer.from(nonce, 'utf8').toString('base64url'),
    state,
    session_state: 'mock-session-state',
    iss: ISSUER,
    scope: 'openid',
  }).toString();

  await route.fulfill({ status: 302, headers: { location: target.toString() } });
}

async function handleToken(
  route: Route,
  clientRoles: string[],
  username: string,
  cors: Record<string, string>,
): Promise<void> {
  const body = new URLSearchParams(route.request().postData() ?? '');
  const code = body.get('code');
  let nonce = 'mock-nonce';
  if (code) {
    try {
      nonce = Buffer.from(code, 'base64url').toString('utf8');
    } catch {
      nonce = 'mock-nonce';
    }
  }

  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: cors,
    body: JSON.stringify(buildTokenSet(clientRoles, username, nonce)),
  });
}

async function handleLogout(route: Route): Promise<void> {
  const url = new URL(route.request().url());
  const redirect = url.searchParams.get('post_logout_redirect_uri');
  if (redirect) {
    await route.fulfill({ status: 302, headers: { location: redirect } });
    return;
  }
  await route.fulfill({
    status: 200,
    contentType: 'text/html',
    body: '<html><body>Logged out</body></html>',
  });
}
