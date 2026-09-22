import { computed, inject, Injectable, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { map, of } from 'rxjs';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { httpErrorMessage } from '@shared/data';

/** Where the resolved store came from; `none` means the screens must fail closed. */
export type VariantStoreSource = 'query' | 'profile' | 'none';

/**
 * Resolves the store the per-store variant screens operate on.
 *
 * The store reaches these screens as an **id**, never as a picker:
 *
 * 1. `?storeId=` in the URL wins, so a link can point at an exact store;
 * 2. otherwise the authenticated user's configured store from `GET /api/profile`
 *    (backed by `user_preferences`, not by a token claim);
 * 3. otherwise `null`, and the screens **fail closed** — no store-scoped request
 *    is issued — instead of guessing a store. Same rule as the
 *    `store-inventory-settings` mold.
 *
 * The five-level chain is deliberately not carried here: the backend derives and
 * verifies company → country → region → zone → store from the single `storeId`, so
 * the extra levels would be payload these screens never send.
 *
 * Provided per component rather than at the root: each screen owns its own
 * resolution and there is no cross-screen state worth sharing.
 */
@Injectable()
export class VariantStoreContext {
  private readonly profileService = inject(ProfileService);

  /**
   * The resolution request, set by the owning page from its own `ActivatedRoute`
   * snapshot. `undefined` keeps the resource idle, so a page that never asks
   * issues no request at all.
   */
  private readonly request = signal<{ queryStoreId: string | null } | undefined>(undefined);

  private readonly resolution = rxResource({
    params: () => this.request(),
    stream: ({ params }) =>
      params.queryStoreId
        ? of<string | null>(params.queryStoreId)
        : this.profileService.getProfile().pipe(map((profile) => profile.companyStoreId ?? null)),
  });

  /**
   * Asks for a resolution and is idempotent for the same input, so a page may call
   * it from its constructor without guarding the call site.
   */
  resolve(queryStoreId: string | null): void {
    if (this.request()?.queryStoreId === queryStoreId) {
      return;
    }
    this.request.set({ queryStoreId });
  }

  /**
   * Re-runs the resolution. Only meaningful after {@link resolve}; used by the
   * fail-closed state's retry, which exists because a profile read can fail on its
   * own.
   */
  reload(): void {
    this.resolution.reload();
  }

  /**
   * The resolved store, or `null` while unresolved or after a failure. A guarded
   * resource read: `.value()` is never touched in an error state.
   */
  readonly storeId = computed<string | null>(() =>
    this.resolution.hasValue() ? this.resolution.value() : null,
  );

  /**
   * Whether the screens have nothing to render yet: the request was never made or
   * it is still in flight. Distinct from the fail-closed state, which is a settled
   * resolution with no store.
   */
  readonly pending = computed(() => this.request() === undefined || this.resolution.isLoading());

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
  readonly storeError = computed<string | null>(() => {
    const error = this.resolution.error();
    return error ? httpErrorMessage(error) : null;
  });

  /** Whether the resolution settled without a store and without a failure. */
  readonly unconfigured = computed(
    () => !this.pending() && !this.storeError() && this.storeId() === null,
  );
}
