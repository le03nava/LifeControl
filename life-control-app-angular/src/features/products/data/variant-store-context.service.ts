import { computed, inject, Injectable, signal } from '@angular/core';
import { LocationPreference } from '@features/user/profile/data/location-preference.service';

/** Where the resolved store came from; `none` means the screens must fail closed. */
export type VariantStoreSource = 'query' | 'profile' | 'none';

/**
 * Resolves the store the per-store variant screens operate on.
 *
 * The store reaches these screens as an **id**, never as a picker:
 *
 * 1. `?storeId=` in the URL wins, so a link can point at an exact store;
 * 2. otherwise the authenticated user's location chain from `GET /api/profile`
 *    (backed by `user_preferences`, not by a token claim);
 * 3. otherwise `null`, and the screens **fail closed** — no store-scoped request
 *    is issued — instead of guessing a store. Same rule as the
 *    `store-inventory-settings` mold.
 *
 * The precedence itself lives in {@link LocationPreference} (`S6`), the one shared
 * resolution both store-scoped features delegate to; this class is the per-screen
 * surface those screens already speak. Its public shape is unchanged on purpose, so
 * every screen and spec that consumed the previous copy keeps working —
 * `D39`'s per-feature copy is retired, not its API.
 *
 * The five-level chain is deliberately not carried here: the backend derives and
 * verifies company → country → region → zone → store from the single `storeId`, so
 * the extra levels would be payload these screens never send.
 *
 * Provided per component rather than at the root: each screen owns its own
 * resolution request, and that is what tells it whether *it* has asked yet.
 */
@Injectable()
export class VariantStoreContext {
  private readonly locationPreference = inject(LocationPreference);

  /**
   * The resolution request, set by the owning page from its own `ActivatedRoute`
   * snapshot. `undefined` keeps the resource idle, so a page that never asks
   * issues no request at all.
   */
  private readonly request = signal<{ queryStoreId: string | null } | undefined>(undefined);

  /**
   * Asks for a resolution and is idempotent for the same input, so a page may call
   * it from its constructor without guarding the call site.
   */
  resolve(queryStoreId: string | null): void {
    if (this.request()?.queryStoreId === queryStoreId) {
      return;
    }
    this.request.set({ queryStoreId });
    this.locationPreference.resolve({ companyStoreId: queryStoreId });
  }

  /**
   * Re-runs the resolution. Only meaningful after {@link resolve}; used by the
   * fail-closed state's retry, which exists because a profile read can fail on its
   * own.
   */
  reload(): void {
    this.locationPreference.reload();
  }

  /**
   * Whether the screens have nothing to render yet: this screen never asked, or its
   * own resolution has neither produced a location nor failed.
   *
   * Deliberately not `rxResource.isLoading()`: that flag turns true one reactive
   * step *after* the params are defined, so a consumer gating its own read on it can
   * be evaluated before the load starts, see `false`, and issue a read with no store
   * followed by a second one with the store. Settling on `hasValue() || error`
   * is order-independent.
   */
  readonly pending = computed(
    () => this.request() === undefined || !this.locationPreference.settled(),
  );

  /**
   * The resolved store, or `null` while unresolved or after a failure. A guarded
   * read: nothing is reported before this screen's own resolution settles.
   */
  readonly storeId = computed<string | null>(() =>
    this.pending() ? null : this.locationPreference.companyStoreId(),
  );

  /** Whether the store came from the URL or from the user's profile. */
  readonly source = computed<VariantStoreSource>(() => {
    const storeId = this.storeId();
    if (!storeId) {
      return 'none';
    }
    return this.request()?.queryStoreId === storeId ? 'query' : 'profile';
  });

  /**
   * The resolution failure, or `null`.
   *
   * A failed resolution is not a store that was never configured: the profile read
   * can fail on its own, and reporting that as "no store configured" would state
   * something the server never said. Kept separate so the fail-closed copy can tell
   * the two apart instead of collapsing them.
   */
  readonly storeError = computed<string | null>(() => this.locationPreference.error());

  /** Whether the resolution settled without a store and without a failure. */
  readonly unconfigured = computed(
    () => !this.pending() && !this.storeError() && this.storeId() === null,
  );
}
