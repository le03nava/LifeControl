import { computed, inject, Injectable, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, map, of, tap, throwError } from 'rxjs';
import { httpErrorMessage } from '@shared/data';
import { LocationChain } from './profile.models';
import { ProfileService } from './profile.service';

/**
 * The levels a call site may take from its own route's query parameters.
 *
 * Partial on purpose: a screen overrides only the levels its URL names. `null` is accepted as "this
 * level is not named" — that is what a cleared `mat-select` reports and what an absent query
 * parameter reads as — so a call site can pass the value straight through.
 */
export type LocationOverride = { [K in keyof LocationChain]?: string | null };

/** Marks the shared profile layer, so every call site that overrides nothing reads the profile once. */
const PROFILE_LAYER_KEY = 'profile';

/**
 * The identity of a resolution. Two call sites asking the same question share one key, which is what
 * makes the profile layer a single read and what keeps a chain resolved for one override from being
 * reported to a call site that asked for another.
 */
function resolutionKey(overrides: LocationOverride | undefined): string {
  const chain = chainOf(overrides);
  return chain ? JSON.stringify(chain) : PROFILE_LAYER_KEY;
}

/**
 * The chain a set of overrides describes, or `null` when it names no level at all.
 *
 * All-or-nothing on purpose: a route that names a store answers the store question and the profile is
 * not consulted for the rest, because a chain stitched together from two sources would pair one
 * company's ancestors with another company's store. A level the route did not name — absent, `null` or
 * the empty string a cleared control writes — is materialized as the empty string.
 */
function chainOf(overrides: LocationOverride | undefined): LocationChain | null {
  if (!overrides) {
    return null;
  }
  const named = [
    overrides.companyId,
    overrides.companyCountryId,
    overrides.companyRegionId,
    overrides.companyZoneId,
    overrides.companyStoreId,
  ].some((id) => !!id);
  if (!named) {
    return null;
  }
  return {
    companyId: overrides.companyId ?? '',
    companyCountryId: overrides.companyCountryId ?? '',
    companyRegionId: overrides.companyRegionId ?? '',
    companyZoneId: overrides.companyZoneId ?? '',
    companyStoreId: overrides.companyStoreId ?? '',
    companyStoreName: overrides.companyStoreName ?? '',
  };
}

/** A level the chain does not name, reported as absent rather than as the empty string. */
function idOf(id: string | undefined): string | null {
  return id ? id : null;
}

/**
 * The one location resolution of the app: a call site's override → the profile's `location` → `null`
 * (`S6` of `location-preference`).
 *
 * It replaces the two per-feature copies that used to resolve a store each
 * (`products/data/variant-store-context.service.ts` and `scheduling/data/scheduling-store-context.service.ts`,
 * `D39`), which now keep their public surface and delegate here. Both features already imported
 * `ProfileService`, so sharing this service adds no dependency edge (`L7`).
 *
 * **Wait for the chain, never for `rxResource.isLoading()`.** A consumer gates its own reads on
 * {@link settled}, which is order-independent: `isLoading()` turns true one reactive step after the
 * params are defined, so a consumer evaluated in between would see `false`, issue a read with no
 * location and a second one with it.
 *
 * **The resolution is answered only for the override that asked for it.** A chain resolved for
 * `?storeId=A` is not an answer to a call site asking for `B`, so {@link chain} stays `null` until
 * that call site's own resolution settles. That is also what makes the profile layer one read: every
 * call site that overrides nothing shares the same key, so the second one reads the settled value.
 */
@Injectable({
  providedIn: 'root',
})
export class LocationPreference {
  private readonly profileService = inject(ProfileService);

  /**
   * The call site's own request, set by {@link resolve}. `undefined` keeps the resource idle, so an
   * app that never asks issues no profile read at all.
   */
  private readonly request = signal<{ key: string; overrides: LocationOverride } | undefined>(
    undefined,
  );

  /** The key of the last resolution that settled, successful or not. */
  private readonly resolvedKey = signal<string | null>(null);

  private readonly resolution = rxResource({
    params: () => this.request(),
    stream: ({ params }) => {
      const chain = chainOf(params.overrides);
      const settled = () => this.resolvedKey.set(params.key);
      const source = chain
        ? of<LocationChain | null>(chain)
        : this.profileService.getProfile().pipe(map((profile) => profile.location ?? null));

      return source.pipe(
        tap(settled),
        // A failure still settles: reporting it as pending forever would hide the retry the
        // fail-closed state offers, and reporting it as "no location" would state something the
        // server never said.
        catchError((error: unknown) => {
          settled();
          return throwError(() => error);
        }),
      );
    },
  });

  /**
   * Asks for a resolution and is idempotent for the same input, so a page may call it from its
   * constructor without guarding the call site. `undefined` — or an override naming no level —
   * resolves the profile's own chain.
   */
  resolve(overrides: LocationOverride = {}): void {
    const key = resolutionKey(overrides);
    if (this.request()?.key === key) {
      return;
    }
    this.request.set({ key, overrides });
  }

  /**
   * Re-runs the resolution. Only meaningful after {@link resolve}; used by the fail-closed state's
   * retry, which exists because a profile read can fail on its own.
   */
  reload(): void {
    this.resolution.reload();
  }

  /**
   * The key the latest {@link resolve} asked for: the override's own chain, or the shared profile
   * layer when it names no level.
   */
  private readonly key = computed(() => resolutionKey(this.request()?.overrides));

  /** Whether the resolved chain belongs to the latest request. */
  private readonly isCurrent = computed(() => this.resolvedKey() === this.key());

  /** The resolution failure, or `null`. Kept apart from "no location" (`S8`). */
  readonly error = computed<string | null>(() => {
    const error = this.resolution.error();
    return error && this.isCurrent() ? httpErrorMessage(error) : null;
  });

  /** Whether the current request has settled: it has a chain, or it failed. */
  readonly settled = computed(
    () =>
      this.request() !== undefined &&
      this.isCurrent() &&
      (this.resolution.hasValue() || this.resolution.error() !== undefined),
  );

  /** Whether a request was made and has not settled yet. */
  readonly pending = computed(() => this.request() !== undefined && !this.settled());

  /**
   * The resolved chain, or `null` while unresolved, after a failure, or when there is nothing to
   * report — the screens fail closed on it instead of guessing a location (`S8`).
   */
  readonly chain = computed<LocationChain | null>(() =>
    this.settled() && this.resolution.hasValue() ? this.resolution.value() : null,
  );

  // ─── The chain, level by level (`S6`: the five ids plus the whole chain) ───

  readonly companyId = computed<string | null>(() => idOf(this.chain()?.companyId));
  readonly companyCountryId = computed<string | null>(() => idOf(this.chain()?.companyCountryId));
  readonly companyRegionId = computed<string | null>(() => idOf(this.chain()?.companyRegionId));
  readonly companyZoneId = computed<string | null>(() => idOf(this.chain()?.companyZoneId));
  readonly companyStoreId = computed<string | null>(() => idOf(this.chain()?.companyStoreId));
}
