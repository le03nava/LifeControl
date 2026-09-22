/// <reference types="vitest/globals" />
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { VariantStoreContext } from './variant-store-context.service';

/**
 * Host component: `VariantStoreContext` is provided per screen, and its
 * `rxResource` resolves during change detection, so the spec drives it through a
 * fixture instead of touching the signal graph directly.
 */
@Component({
  selector: 'app-variant-store-host',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
})
class HostComponent {
  readonly context = inject(VariantStoreContext);
}

describe('VariantStoreContext', () => {
  let fixture: ComponentFixture<HostComponent>;
  let context: VariantStoreContext;
  let getProfile: ReturnType<typeof vi.fn>;

  const profile = (companyStoreId: string | null): ProfileResponse => ({
    keycloakUserId: 'user-1',
    username: 'operator',
    email: 'operator@lifecontrol.test',
    firstName: 'Oper',
    lastName: 'Ator',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId,
  });

  beforeEach(() => {
    getProfile = vi.fn((): Observable<ProfileResponse> => of(profile('store-from-profile')));
    TestBed.configureTestingModule({
      providers: [VariantStoreContext, { provide: ProfileService, useValue: { getProfile } }],
    });
    context = TestBed.inject(VariantStoreContext);
  });

  /** Creates the fixture, asks for the resolution and settles the resource. */
  async function resolve(queryStoreId: string | null): Promise<void> {
    fixture = TestBed.createComponent(HostComponent);
    context.resolve(queryStoreId);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('should be created', () => {
    expect(context).toBeTruthy();
  });

  it('should stay pending and issue no request until a page asks for a resolution', () => {
    expect(context.pending()).toBe(true);
    expect(context.storeId()).toBeNull();
    expect(context.source()).toBe('none');
    expect(getProfile).not.toHaveBeenCalled();
  });

  it('should use the query param store without reading the profile', async () => {
    await resolve('store-from-url');

    expect(context.storeId()).toBe('store-from-url');
    expect(context.source()).toBe('query');
    expect(context.pending()).toBe(false);
    // The URL already answers the question: the profile read must not happen.
    expect(getProfile).not.toHaveBeenCalled();
  });

  it('should fall back to the authenticated user store when the URL carries none', async () => {
    await resolve(null);

    expect(context.storeId()).toBe('store-from-profile');
    expect(context.source()).toBe('profile');
    expect(context.pending()).toBe(false);
    expect(getProfile).toHaveBeenCalledTimes(1);
  });

  it('should fail closed and call the store unconfigured when the profile has none', async () => {
    getProfile.mockReturnValue(of(profile(null)));

    await resolve(null);

    expect(context.storeId()).toBeNull();
    expect(context.source()).toBe('none');
    expect(context.unconfigured()).toBe(true);
    expect(context.storeError()).toBeNull();
  });

  it('should surface a failed profile read instead of reporting the store as unconfigured', async () => {
    getProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    await resolve(null);

    expect(context.storeId()).toBeNull();
    // A failed read is not the same fact as a store that was never configured.
    expect(context.storeError()).not.toBeNull();
    expect(context.unconfigured()).toBe(false);
  });

  it('should not re-run the resolution when asked for the same store again', async () => {
    await resolve(null);
    context.resolve(null);
    await fixture.whenStable();

    expect(getProfile).toHaveBeenCalledTimes(1);
  });

  it('should re-run the resolution on reload', async () => {
    await resolve(null);
    expect(getProfile).toHaveBeenCalledTimes(1);

    context.reload();
    await fixture.whenStable();

    expect(getProfile).toHaveBeenCalledTimes(2);
  });

  it('should re-resolve when the page asks for a different store', async () => {
    await resolve(null);
    await resolve('store-from-url');

    expect(context.storeId()).toBe('store-from-url');
    expect(context.source()).toBe('query');
  });
});
