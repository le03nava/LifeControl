import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CompanyStoreService } from './company-store.service';
import { CompanyStore, StoreRequest } from '../models/store.models';

describe('CompanyStoreService', () => {
  let service: CompanyStoreService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const countryId = 'cc-1';
  const regionId = 'region-1';
  const zoneId = 'zone-1';
  const baseUrl = 'http://localhost:9000/api';

  const mockStores: CompanyStore[] = [
    {
      id: 'store-1',
      companyId: 'company-123',
      companyCountryId: 'cc-1',
      regionId: 'region-1',
      zoneId: 'zone-1',
      storeName: 'Tienda Central',
      email: 'central@store.com',
      phoneNumber: '+525512345678',
      street: 'Av. Reforma',
      streetNumber: '222',
      neighborhood: 'Juárez',
      zipCode: '06600',
      city: 'Ciudad de México',
      state: 'CDMX',
      countryId: 'MX',
      enabled: true,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
    },
    {
      id: 'store-2',
      companyId: 'company-123',
      companyCountryId: 'cc-1',
      regionId: 'region-1',
      zoneId: 'zone-1',
      storeName: 'Tienda Norte',
      email: 'norte@store.com',
      enabled: false,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-15T00:00:00Z',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyStoreService],
    });
    service = TestBed.inject(CompanyStoreService);
    httpMock = TestBed.inject(HttpTestingController);
    (service as any).configService = { apiUrl: baseUrl };
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getStores', () => {
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores`;

    it('should fetch stores for a zone', async () => {
      const storesPromise = firstValueFrom(service.getStores(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(mockStores);

      const stores = await storesPromise;
      expect(stores).toEqual(mockStores);
      expect(stores.length).toBe(2);
    });

    it('should populate the stores signal', async () => {
      const fetchPromise = firstValueFrom(service.getStores(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush(mockStores);

      await fetchPromise;

      expect(service.stores()).toEqual(mockStores);
      expect(service.stores().length).toBe(2);
    });

    it('should pass includeDisabled param when true', async () => {
      const storesPromise = firstValueFrom(service.getStores(companyId, countryId, regionId, zoneId, true));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(mockStores);

      await storesPromise;
    });

    it('should set loading true during fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const fetchPromise = firstValueFrom(service.getStores(companyId, countryId, regionId, zoneId));

      await new Promise(resolve => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush(mockStores);

      await fetchPromise;
      expect(service.loading()).toBe(false);
    });

    it('should set error signal on HTTP failure', async () => {
      const errorPromise = firstValueFrom(service.getStores(companyId, countryId, regionId, zoneId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(errorPromise).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar las tiendas');
    });
  });

  describe('getStore', () => {
    const storeId = 'store-1';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}`;

    it('should fetch a single store by id', async () => {
      const storePromise = firstValueFrom(service.getStore(companyId, countryId, regionId, zoneId, storeId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'GET'
      );
      req.flush(mockStores[0]);

      const store = await storePromise;
      expect(store).toEqual(mockStores[0]);
      expect(store.id).toBe(storeId);
    });

    it('should set error on fetch failure', async () => {
      const storePromise = firstValueFrom(service.getStore(companyId, countryId, regionId, zoneId, storeId));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Not found', { status: 404, statusText: 'Not Found' });

      await expect(storePromise).rejects.toThrow();
      expect(service.error()).toBe('Error al cargar la tienda');
    });
  });

  describe('addStore', () => {
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores`;
    const request: StoreRequest = {
      storeName: 'New Store',
      email: 'new@store.com',
      phoneNumber: '+525598765432',
      street: 'Calle 1',
      streetNumber: '100',
      neighborhood: 'Centro',
      zipCode: '06000',
      city: 'CDMX',
      state: 'CDMX',
      countryId: 'MX',
    };

    const response: CompanyStore = {
      id: 'store-3',
      companyId: 'company-123',
      companyCountryId: 'cc-1',
      regionId: 'region-1',
      zoneId: 'zone-1',
      storeName: 'New Store',
      email: 'new@store.com',
      phoneNumber: '+525598765432',
      street: 'Calle 1',
      streetNumber: '100',
      neighborhood: 'Centro',
      zipCode: '06000',
      city: 'CDMX',
      state: 'CDMX',
      countryId: 'MX',
      enabled: true,
      createdAt: '2024-02-01T00:00:00Z',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should POST and push the new store to the signal', async () => {
      (service as any)._stores.set([mockStores[0]]);
      expect(service.stores().length).toBe(1);

      const addPromise = firstValueFrom(service.addStore(companyId, countryId, regionId, zoneId, request));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'POST'
      );
      expect(req.request.body).toEqual(request);
      req.flush(response);

      const store = await addPromise;
      expect(store).toEqual(response);
      expect(service.stores().length).toBe(2);
      expect(service.stores()[1].storeName).toBe('New Store');
    });

    it('should set error on add failure', async () => {
      const addPromise = firstValueFrom(service.addStore(companyId, countryId, regionId, zoneId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();
      expect(service.error()).toBe('Error al crear la tienda');
    });

    it('should NOT modify signal on error', async () => {
      (service as any)._stores.set([mockStores[0]]);

      const addPromise = firstValueFrom(service.addStore(companyId, countryId, regionId, zoneId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await expect(addPromise).rejects.toThrow();
      expect(service.stores().length).toBe(1);
    });
  });

  describe('updateStore', () => {
    const storeId = 'store-1';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}`;
    const request: StoreRequest = {
      storeName: 'Updated Store',
    };

    const updatedStore: CompanyStore = {
      id: 'store-1',
      companyId: 'company-123',
      companyCountryId: 'cc-1',
      regionId: 'region-1',
      zoneId: 'zone-1',
      storeName: 'Updated Store',
      enabled: true,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should PUT and replace the store in the signal', async () => {
      (service as any)._stores.set(mockStores);
      expect(service.stores().length).toBe(2);

      const updatePromise = firstValueFrom(service.updateStore(companyId, countryId, regionId, zoneId, storeId, request));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'PUT'
      );
      expect(req.request.body).toEqual(request);
      req.flush(updatedStore);

      const store = await updatePromise;
      expect(store).toEqual(updatedStore);
      expect(service.stores().length).toBe(2);
      expect(service.stores()[0].storeName).toBe('Updated Store');
    });

    it('should set error on update failure', async () => {
      const updatePromise = firstValueFrom(service.updateStore(companyId, countryId, regionId, zoneId, storeId, request));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(updatePromise).rejects.toThrow();
      expect(service.error()).toBe('Error al actualizar la tienda');
    });
  });

  describe('removeStore (disable)', () => {
    const storeId = 'store-1';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}`;

    it('should DELETE and set store enabled to false in signal', async () => {
      (service as any)._stores.set(mockStores);
      expect(service.stores().length).toBe(2);

      const removePromise = firstValueFrom(service.removeStore(companyId, countryId, regionId, zoneId, storeId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'DELETE'
      );
      req.flush(null);

      await removePromise;

      expect(service.stores().length).toBe(2);
      const store = service.stores().find(s => s.id === storeId);
      expect(store).toBeDefined();
      expect(store!.enabled).toBe(false);
    });

    it('should set error on remove failure', async () => {
      const removePromise = firstValueFrom(service.removeStore(companyId, countryId, regionId, zoneId, storeId));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(removePromise).rejects.toThrow();
      expect(service.error()).toBe('Error al deshabilitar la tienda');
    });
  });

  describe('enableStore', () => {
    const storeId = 'store-2';
    const expectedUrl = `${baseUrl}/companies/${companyId}/countries/${countryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}`;
    const enabledStore: CompanyStore = {
      id: 'store-2',
      companyId: 'company-123',
      companyCountryId: 'cc-1',
      regionId: 'region-1',
      zoneId: 'zone-1',
      storeName: 'Tienda Norte',
      enabled: true,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-02-01T00:00:00Z',
    };

    it('should PATCH and update store enabled state in signal', async () => {
      (service as any)._stores.set(mockStores);
      expect(service.stores().length).toBe(2);

      const enablePromise = firstValueFrom(service.enableStore(companyId, countryId, regionId, zoneId, storeId));

      const req = httpMock.expectOne(
        r => r.url === expectedUrl && r.method === 'PATCH'
      );
      req.flush(enabledStore);

      const store = await enablePromise;
      expect(store).toEqual(enabledStore);
      expect(store.enabled).toBe(true);
      expect(service.stores().find(s => s.id === storeId)?.enabled).toBe(true);
    });

    it('should set error on enable failure', async () => {
      const enablePromise = firstValueFrom(service.enableStore(companyId, countryId, regionId, zoneId, storeId));

      const req = httpMock.expectOne(expectedUrl);
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });

      await expect(enablePromise).rejects.toThrow();
      expect(service.error()).toBe('Error al reactivar la tienda');
    });
  });

  describe('clearError', () => {
    it('should reset the error signal to null', () => {
      (service as any)._error.set('Some error');
      expect(service.error()).toBe('Some error');

      service.clearError();
      expect(service.error()).toBeNull();
    });
  });

  describe('signal exposure', () => {
    it('should expose readonly signals for stores, loading, and error', () => {
      expect(service.stores).toBeDefined();
      expect(service.loading).toBeDefined();
      expect(service.error).toBeDefined();

      expect(service.stores()).toEqual([]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });
});
