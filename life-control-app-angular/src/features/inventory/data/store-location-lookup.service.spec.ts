import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { StoreLocationLookupService } from './store-location-lookup.service';
import { ConfigService } from '@app/services/config.service';
import type { StoreChain, StoreLocationSummary } from '../models/store-location-summary.models';

const TEST_API = 'http://test/api';

const mockChain: StoreChain = {
  companyId: 'company-1',
  companyCountryId: 'country-1',
  regionId: 'region-1',
  zoneId: 'zone-1',
  storeId: 'store-1',
};

const EXPECTED_URL = `${TEST_API}/companies/company-1/countries/country-1/regions/region-1/zones/zone-1/stores/store-1/store-locations`;

const mockLocations: StoreLocationSummary[] = [
  {
    id: 'loc-1',
    locationCode: 'ALM-01',
    locationName: 'Almacén Central',
    storeZoneId: 'zone-1',
    zoneCode: 'Z-01',
    zoneName: 'Depósito',
    storeAreaId: 'area-1',
    areaCode: 'A-01',
    areaName: 'Área de Recepción',
  },
];

describe('StoreLocationLookupService', () => {
  let service: StoreLocationLookupService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        StoreLocationLookupService,
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
    });
    service = TestBed.inject(StoreLocationLookupService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getStoreLocations', () => {
    it('should GET the exact nested store-locations URL (direct child of stores)', async () => {
      const promise = firstValueFrom(service.getStoreLocations(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      expect(req.request.method).toBe('GET');
      expect(req.request.url).toBe(EXPECTED_URL);
      req.flush(mockLocations);

      const result = await promise;
      expect(result).toEqual(mockLocations);
      expect(result[0].locationName).toBe('Almacén Central');
    });

    it('should propagate HTTP failure', async () => {
      const promise = firstValueFrom(service.getStoreLocations(mockChain));

      const req = httpMock.expectOne(EXPECTED_URL);
      req.flush({ message: 'Not found' }, { status: 404, statusText: 'Not Found' });

      await expect(promise).rejects.toEqual(expect.objectContaining({ status: 404 }));
    });
  });
});
