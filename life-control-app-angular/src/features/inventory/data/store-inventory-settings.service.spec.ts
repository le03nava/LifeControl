import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { StoreInventorySettingsService } from './store-inventory-settings.service';
import { ConfigService } from '@app/services/config.service';
import { SKIP_ERROR_NOTIFICATION } from '@shared/data/skip-error-notification';
import type { StoreChain, StoreInventorySettings } from '../models/store-location-summary.models';

const TEST_API = 'http://test/api';

const mockChain: StoreChain = {
  companyId: 'company-1',
  companyCountryId: 'country-1',
  regionId: 'region-1',
  zoneId: 'zone-1',
  storeId: 'store-1',
};

const EXPECTED_URL = `${TEST_API}/companies/company-1/countries/country-1/regions/region-1/zones/zone-1/stores/store-1/inventory-settings`;

const mockSettings: StoreInventorySettings = {
  companyStoreId: 'store-1',
  receivingLocationId: 'loc-receive',
  salesLocationId: 'loc-sales',
};

describe('StoreInventorySettingsService', () => {
  let service: StoreInventorySettingsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        StoreInventorySettingsService,
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
    });
    service = TestBed.inject(StoreInventorySettingsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getSettings', () => {
    it('should GET the exact nested inventory-settings URL', async () => {
      const promise = firstValueFrom(service.getSettings(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      expect(req.request.method).toBe('GET');
      expect(req.request.url).toBe(EXPECTED_URL);
      req.flush(mockSettings);

      const result = await promise;
      expect(result).toEqual(mockSettings);
    });

    it('should mark the request with SKIP_ERROR_NOTIFICATION', async () => {
      const promise = firstValueFrom(service.getSettings(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(true);
      req.flush(mockSettings);

      await promise;
    });

    it('should map a 200 response to the settings object', async () => {
      const promise = firstValueFrom(service.getSettings(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush(mockSettings);

      const result = await promise;
      expect(result).not.toBeNull();
      expect(result?.receivingLocationId).toBe('loc-receive');
      expect(result?.salesLocationId).toBe('loc-sales');
    });

    it('should emit null on 404 WITHOUT rethrowing', async () => {
      const promise = firstValueFrom(service.getSettings(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush({ message: 'Settings not configured' }, { status: 404, statusText: 'Not Found' });

      await expect(promise).resolves.toBeNull();
    });

    it('should rethrow a 500 unchanged', async () => {
      const promise = firstValueFrom(service.getSettings(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush({ message: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 500 }));
    });

    it('should rethrow a non-HTTP error unchanged', async () => {
      // A 403 is not the expected "unconfigured store" state and must propagate.
      const promise = firstValueFrom(service.getSettings(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush({ message: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 403 }));
    });
  });

  describe('upsertSettings', () => {
    const request = {
      receivingLocationId: 'loc-receive',
      salesLocationId: 'loc-sales',
    };

    it('should PUT the exact nested inventory-settings URL with the request body', async () => {
      const promise = firstValueFrom(service.upsertSettings(mockChain, request));

      const req = httpMock.expectOne(EXPECTED_URL);
      expect(req.request.method).toBe('PUT');
      expect(req.request.url).toBe(EXPECTED_URL);
      expect(req.request.body).toEqual(request);
      req.flush(mockSettings);

      const result = await promise;
      expect(result).toEqual(mockSettings);
    });

    it('should NOT mark the write with SKIP_ERROR_NOTIFICATION', async () => {
      const promise = firstValueFrom(service.upsertSettings(mockChain, request));

      const req = httpMock.expectOne(EXPECTED_URL);
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush(mockSettings);

      await promise;
    });

    it.each([400, 404, 500])('should reject a %s without swallowing it', async (status) => {
      const promise = firstValueFrom(service.upsertSettings(mockChain, request));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush({ message: 'Write rejected' }, { status, statusText: 'Error' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status }));
    });

    it('should reject a 404 instead of mapping it to null like the read does', async () => {
      const promise = firstValueFrom(service.upsertSettings(mockChain, request));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush({ message: 'Store not found' }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 404 }));
    });
  });
});
