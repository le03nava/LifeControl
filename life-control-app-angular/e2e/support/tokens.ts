import { randomUUID } from 'node:crypto';

const ISSUER = 'http://localhost:8181/realms/life-control-realm';
const CLIENT_ID = 'life-control-client';

const base64Url = (input: Record<string, unknown>): string =>
  Buffer.from(JSON.stringify(input)).toString('base64url');

const now = (): number => Math.floor(Date.now() / 1000);

const randomSignature = (): string => Buffer.from(randomUUID()).toString('base64url');

const header = base64Url({ alg: 'RS256', typ: 'JWT', kid: 'mock-e2e-key' });

function accessToken(roles: string[], username: string): string {
  const payload = {
    exp: now() + 3600,
    iat: now(),
    nbf: now(),
    jti: randomUUID(),
    iss: ISSUER,
    sub: username,
    typ: 'Bearer',
    azp: CLIENT_ID,
    aud: 'account',
    sid: 'mock-session',
    auth_time: now(),
    session_state: 'mock-session-state',
    preferred_username: username,
    name: 'E2E User',
    given_name: 'E2E',
    family_name: 'User',
    email: `${username}@example.com`,
    email_verified: true,
    realm_access: {
      roles: ['offline_access', 'uma_authorization', 'default-roles-life-control-realm'],
    },
    resource_access: {
      account: { roles: ['manage-account', 'manage-account-links', 'view-profile'] },
      [CLIENT_ID]: { roles },
    },
  };
  return `${header}.${base64Url(payload)}.${randomSignature()}`;
}

function idToken(username: string, nonce: string): string {
  const payload = {
    exp: now() + 3600,
    iat: now(),
    nbf: now(),
    jti: randomUUID(),
    iss: ISSUER,
    sub: username,
    typ: 'ID',
    azp: CLIENT_ID,
    nonce,
    session_state: 'mock-session-state',
    preferred_username: username,
    name: 'E2E User',
    given_name: 'E2E',
    family_name: 'User',
    email: `${username}@example.com`,
    email_verified: true,
  };
  return `${header}.${base64Url(payload)}.${randomSignature()}`;
}

function refreshToken(username: string): string {
  const payload = {
    exp: now() + 1800,
    iat: now(),
    jti: randomUUID(),
    iss: ISSUER,
    sub: username,
    typ: 'Refresh',
  };
  return `${header}.${base64Url(payload)}.${randomSignature()}`;
}

export interface TokenSet {
  access_token: string;
  refresh_token: string;
  id_token: string;
  token_type: 'Bearer';
  expires_in: number;
  refresh_expires_in: number;
  session_state: string;
  scope: string;
}

/**
 * Builds a Keycloak token endpoint response with fabricated JWTs.
 * The id_token carries the nonce that keycloak-js expects back on the code flow.
 */
export function buildTokenSet(roles: string[], username: string, nonce: string): TokenSet {
  return {
    access_token: accessToken(roles, username),
    refresh_token: refreshToken(username),
    id_token: idToken(username, nonce),
    token_type: 'Bearer',
    expires_in: 3600,
    refresh_expires_in: 1800,
    session_state: 'mock-session-state',
    scope: 'openid profile email',
  };
}
