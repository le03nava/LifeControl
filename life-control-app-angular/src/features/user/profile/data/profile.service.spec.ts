import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ProfileService } from './profile.service';
import { ProfileResponse, ProfileUpdateRequest } from './profile.models';

describe('ProfileService', () => {
  let service: ProfileService;
  let httpMock: HttpTestingController;

  const mockProfile: ProfileResponse = {
    keycloakUserId: 'user-123',
    username: 'johndoe',
    email: 'john@example.com',
    firstName: 'John',
    lastName: 'Doe',
    companyCountryId: null,
    companyId: null,
    companyRegionId: null,
    companyZoneId: null,
    companyStoreId: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProfileService],
    });
    service = TestBed.inject(ProfileService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getProfile', () => {
    it('should fetch the user profile via GET', async () => {
      const profilePromise = firstValueFrom(service.getProfile());

      const req = httpMock.expectOne((r) => r.url.includes('/profile') && r.method === 'GET');
      expect(req.request.method).toBe('GET');
      req.flush(mockProfile);

      const profile = await profilePromise;
      expect(profile).toEqual(mockProfile);
      expect(profile.email).toBe('john@example.com');
    });

    it('should return keycloakUserId from the response', async () => {
      const profilePromise = firstValueFrom(service.getProfile());

      const req = httpMock.expectOne((r) => r.url.includes('/profile'));
      req.flush(mockProfile);

      const profile = await profilePromise;
      expect(profile.keycloakUserId).toBe('user-123');
    });

    it('should return null location fields when user has no preferences', async () => {
      const profilePromise = firstValueFrom(service.getProfile());

      const req = httpMock.expectOne((r) => r.url.includes('/profile'));
      req.flush(mockProfile);

      const profile = await profilePromise;
      expect(profile.companyId).toBeNull();
      expect(profile.companyCountryId).toBeNull();
      expect(profile.companyRegionId).toBeNull();
      expect(profile.companyZoneId).toBeNull();
      expect(profile.companyStoreId).toBeNull();
    });
  });

  describe('updateProfile', () => {
    it('should update the user profile via PUT', async () => {
      const update: ProfileUpdateRequest = {
        firstName: 'Jane',
        lastName: 'Smith',
        email: 'jane@example.com',
      };

      const updatedProfile: ProfileResponse = {
        ...mockProfile,
        firstName: 'Jane',
        lastName: 'Smith',
        email: 'jane@example.com',
      };

      const updatePromise = firstValueFrom(service.updateProfile(update));

      const req = httpMock.expectOne((r) => r.url.includes('/profile') && r.method === 'PUT');
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(update);
      req.flush(updatedProfile);

      const profile = await updatePromise;
      expect(profile.firstName).toBe('Jane');
      expect(profile.lastName).toBe('Smith');
      expect(profile.email).toBe('jane@example.com');
    });

    it('should update location preferences', async () => {
      const update: ProfileUpdateRequest = {
        companyId: 'comp-1',
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
        companyZoneId: 'zone-1',
        companyStoreId: 'store-1',
      };

      const updatedProfile: ProfileResponse = {
        ...mockProfile,
        companyId: 'comp-1',
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
        companyZoneId: 'zone-1',
        companyStoreId: 'store-1',
      };

      const updatePromise = firstValueFrom(service.updateProfile(update));

      const req = httpMock.expectOne((r) => r.url.includes('/profile') && r.method === 'PUT');
      expect(req.request.body).toEqual(update);
      req.flush(updatedProfile);

      const profile = await updatePromise;
      expect(profile.companyId).toBe('comp-1');
      expect(profile.companyCountryId).toBe('cc-1');
      expect(profile.companyStoreId).toBe('store-1');
    });

    it('should send partial update with only email', async () => {
      const update: ProfileUpdateRequest = { email: 'new@example.com' };

      const updatePromise = firstValueFrom(service.updateProfile(update));

      const req = httpMock.expectOne((r) => r.url.includes('/profile') && r.method === 'PUT');
      expect(req.request.body).toEqual({ email: 'new@example.com' });
      expect(req.request.body.firstName).toBeUndefined();
      expect(req.request.body.lastName).toBeUndefined();
      req.flush({ ...mockProfile, email: 'new@example.com' });

      const profile = await updatePromise;
      expect(profile.email).toBe('new@example.com');
    });

    it('should clear location preferences by sending null', async () => {
      const update: ProfileUpdateRequest = {
        companyId: null,
        companyCountryId: null,
        companyRegionId: null,
        companyZoneId: null,
        companyStoreId: null,
      };

      const updatePromise = firstValueFrom(service.updateProfile(update));

      const req = httpMock.expectOne((r) => r.url.includes('/profile') && r.method === 'PUT');
      expect(req.request.body).toEqual(update);
      req.flush(mockProfile);

      const profile = await updatePromise;
      expect(profile.companyId).toBeNull();
    });
  });
});
