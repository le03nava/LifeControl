/// <reference types="vitest/globals" />
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { LocationChain, ProfileResponse } from './profile.models';
import { ProfileService } from './profile.service';
import { LocationPreference } from './location-preference.service';

/**
 * Host component: the service is `providedIn: 'root'` but its `rxResource` resolves during change
 * detection, so the spec drives it through a fixture instead of touching the signal graph directly.
 */
@Component({
  selector: 'app-location-preference-host',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
})
class HostComponent {
  readonly preference = inject(LocationPreference);
}

describe('LocationPreference', () => {
  let fixture: ComponentFixture<HostComponent>;
  let preference: LocationPreference;
  let getProfile: ReturnType<typeof vi.fn>;

  /** The chain `GET /api/profile` derives from the store itself (S1). */
  const chain = (overrides: Partial<LocationChain> = {}): LocationChain => ({
    companyId: 'company-1',
    companyCountryId: 'country-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId: 'store-from-profile',
    companyStoreName: 'Store One',
    ...overrides,
  });

  const profile = (location: LocationChain | null): ProfileResponse => ({
    keycloakUserId: 'user-1',
    username: 'operator',
    email: 'operator@lifecontrol.test',
    firstName: 'Oper',
    lastName: 'Ator',
    // S2: the id keeps the resolution it always had, so it can be non-null while the chain is not.
    companyStoreId: location?.companyStoreId ?? null,
    assignedStores: null,
    location,
  });

  beforeEach(() => {
    getProfile = vi.fn((): Observable<ProfileResponse> => of(profile(chain())));
    TestBed.configureTestingModule({
      providers: [{ provide: ProfileService, useValue: { getProfile } }],
    });
    preference = TestBed.inject(LocationPreference);
  });

  /** Creates the fixture, asks for a resolution and settles the resource. */
  async function resolve(overrides: Parameters<LocationPreference['resolve']>[0]): Promise<void> {
    fixture = TestBed.createComponent(HostComponent);
    preference.resolve(overrides);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /** Settles the reactive pipeline a second time without asking for anything. */
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('should be created', () => {
    expect(preference).toBeTruthy();
  });

  it('should stay idle and issue no profile read until a call site asks (S8)', () => {
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    // Idle, not pending: nothing was asked, so the service is not waiting on anything.
    expect(preference.pending()).toBe(false);
    expect(preference.settled()).toBe(false);
    expect(preference.chain()).toBeNull();
    expect(preference.companyStoreId()).toBeNull();
    expect(getProfile).not.toHaveBeenCalled();
  });

  describe('the three levels of precedence (S6)', () => {
    it('level 2: should resolve the profile chain when the call site overrides nothing', async () => {
      await resolve({});

      expect(preference.chain()).toEqual(chain());
      expect(preference.settled()).toBe(true);
      expect(preference.pending()).toBe(false);
      expect(getProfile).toHaveBeenCalledTimes(1);
    });

    it('level 1: should let the call-site override win over the profile chain', async () => {
      await resolve({ companyStoreId: 'store-from-link' });

      expect(preference.companyStoreId()).toBe('store-from-link');
      // The route already answers the question: the profile read must not happen.
      expect(getProfile).not.toHaveBeenCalled();
    });

    it('level 1: should carry the whole overridden chain, not only the store', async () => {
      await resolve({ companyId: 'company-1', companyCountryId: 'country-2' });

      expect(preference.companyId()).toBe('company-1');
      expect(preference.companyCountryId()).toBe('country-2');
      expect(preference.companyStoreId()).toBeNull();
      expect(getProfile).not.toHaveBeenCalled();
    });

    it('level 3: should resolve to no chain when neither the override nor the profile has one', async () => {
      getProfile.mockReturnValue(of(profile(null)));

      await resolve({});

      expect(preference.chain()).toBeNull();
      expect(preference.settled()).toBe(true);
      expect(preference.pending()).toBe(false);
      expect(preference.error()).toBeNull();
    });

    it('level 3: should treat an override that carries no id as "no override"', async () => {
      await resolve({ companyStoreId: null, companyId: null });

      expect(preference.companyStoreId()).toBe('store-from-profile');
      expect(getProfile).toHaveBeenCalledTimes(1);
    });
  });

  describe('the resolved surface', () => {
    it('should expose the five ids and the whole chain', async () => {
      await resolve({});

      expect(preference.companyId()).toBe('company-1');
      expect(preference.companyCountryId()).toBe('country-1');
      expect(preference.companyRegionId()).toBe('region-1');
      expect(preference.companyZoneId()).toBe('zone-1');
      expect(preference.companyStoreId()).toBe('store-from-profile');
      expect(preference.chain()?.companyStoreName).toBe('Store One');
    });

    it('should report every id as absent while there is no chain to report (S8)', async () => {
      getProfile.mockReturnValue(of(profile(null)));

      await resolve({});

      expect(preference.companyId()).toBeNull();
      expect(preference.companyCountryId()).toBeNull();
      expect(preference.companyRegionId()).toBeNull();
      expect(preference.companyZoneId()).toBeNull();
      expect(preference.companyStoreId()).toBeNull();
    });
  });

  describe('resolution lifecycle', () => {
    it('should be idempotent for the same override', async () => {
      await resolve({});
      preference.resolve({});
      await settle();

      expect(getProfile).toHaveBeenCalledTimes(1);
    });

    it('should re-resolve when the override changes', async () => {
      await resolve({});
      expect(preference.companyStoreId()).toBe('store-from-profile');

      preference.resolve({ companyStoreId: 'store-from-link' });
      await settle();

      expect(preference.companyStoreId()).toBe('store-from-link');
    });

    it('should not report a chain that belongs to a different override', async () => {
      await resolve({});
      expect(preference.companyStoreId()).toBe('store-from-profile');

      // A second call site asks for another store: until its own resolution settles, the previous
      // chain is not an answer to this question.
      preference.resolve({ companyStoreId: 'store-from-link' });

      expect(preference.companyStoreId()).toBeNull();
      expect(preference.settled()).toBe(false);
    });

    it('should share one profile read across call sites that override nothing', async () => {
      await resolve({});
      preference.resolve({ companyStoreId: null });
      await settle();

      expect(getProfile).toHaveBeenCalledTimes(1);
      expect(preference.companyStoreId()).toBe('store-from-profile');
    });

    it('should re-run the resolution on reload', async () => {
      await resolve({});
      expect(getProfile).toHaveBeenCalledTimes(1);

      preference.reload();
      await settle();

      expect(getProfile).toHaveBeenCalledTimes(2);
    });
  });

  describe('failure', () => {
    it('should surface a failed profile read instead of reporting no location', async () => {
      getProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await resolve({});

      expect(preference.chain()).toBeNull();
      expect(preference.error()).not.toBeNull();
      // A failed read is not the same fact as a preference that does not exist.
      expect(preference.settled()).toBe(true);
    });

    it('should not let a failure leak into a later override that needs no profile read', async () => {
      getProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await resolve({});
      expect(preference.error()).not.toBeNull();

      preference.resolve({ companyStoreId: 'store-from-link' });
      await settle();

      expect(preference.error()).toBeNull();
      expect(preference.companyStoreId()).toBe('store-from-link');
    });
  });
});
