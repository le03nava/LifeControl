import { inject, Injectable } from '@angular/core';
import Keycloak from 'keycloak-js';

/**
 * The caller's own Keycloak user id — the `sub` claim — as a narrow accessor.
 *
 * It exists for exactly one rule: **O3** forbids a requester from approving their own access
 * request, and the API enforces it by comparing the persisted `requestedBy` against the caller's
 * `sub` (record T73). A surface that offered the control anyway would be offering a call the API
 * refuses, so the frontend needs the same identity the server compares — and until now **no
 * current-user accessor existed**: the only reader of the token was `core/layout/header/header.ts`,
 * which reads `name`/`preferred_username` for display and never the subject.
 *
 * The mechanism is the header's own and deliberately nothing else: `keycloak.tokenParsed` from the
 * same `keycloak-js` instance every other consumer injects. No new auth path, no dependency, no
 * second source of truth about who is signed in.
 *
 * `sub` **is** Keycloak's user id: it is the value the provisioning record persists as
 * `requestedBy`/`decidedBy` and the value `realm().users().get(userId)` resolves on the server
 * (record T93), so the two sides compare the very same string.
 */

/** The `sub` claim of a parsed token, or `null` when it is absent or blank. */
export function subFromToken(token: Keycloak['tokenParsed'] | undefined): string | null {
  const sub = token?.sub;
  return typeof sub === 'string' && sub.trim() !== '' ? sub : null;
}

/**
 * Injectable accessor for the caller's own `sub`.
 *
 * Must be read in an Angular injection context, like `hasAnyClientRole`. It is evaluated when the
 * value is needed (a getter, not a field) so a surface reads the token of the current session
 * rather than the one captured at construction.
 */
@Injectable({
  providedIn: 'root',
})
export class CurrentUser {
  private readonly keycloak = inject(Keycloak);

  /** The caller's own Keycloak user id, or `null` when the token carries no `sub`. */
  get sub(): string | null {
    return subFromToken(this.keycloak.tokenParsed);
  }
}
