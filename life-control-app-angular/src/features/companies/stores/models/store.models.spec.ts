import { CompanyStore, StoreRequest, StoreSaveEvent, StoreControl } from './store.models';
import { FormControl, FormGroup } from '@angular/forms';

describe('Store Models', () => {
  describe('CompanyStore', () => {
    it('should create a valid CompanyStore object matching the interface', () => {
      const store: CompanyStore = {
        id: 'store-1',
        companyId: 'company-123',
        companyCountryId: 'cc-1',
        regionId: 'region-1',
        zoneId: 'zone-1',
        storeName: 'Tienda Central',
        email: 'central@store.com',
        phoneNumber: '+525512345678',
        address: {
          street: 'Av. Reforma',
          streetNumber: '222',
          internalNumber: 'A-101',
          neighborhood: 'Juárez',
          zipCode: '06600',
          city: 'Ciudad de México',
          state: 'CDMX',
          countryId: 'MX',
        },
        enabled: true,
        createdAt: '2024-01-01T00:00:00Z',
        updatedAt: '2024-01-01T00:00:00Z',
      };

      expect(store).toBeDefined();
      expect(store.id).toBe('store-1');
      expect(store.storeName).toBe('Tienda Central');
      expect(store.email).toBe('central@store.com');
      expect(store.address?.street).toBe('Av. Reforma');
      expect(store.enabled).toBe(true);
    });

    it('should create a CompanyStore with only required fields', () => {
      const store: CompanyStore = {
        id: 'store-2',
        companyId: 'company-123',
        companyCountryId: 'cc-1',
        regionId: 'region-1',
        zoneId: 'zone-1',
        storeName: 'Minimal Store',
        enabled: false,
        createdAt: '2024-01-01T00:00:00Z',
        updatedAt: '2024-01-01T00:00:00Z',
      };

      expect(store.storeName).toBe('Minimal Store');
      expect(store.email).toBeUndefined();
      expect(store.phoneNumber).toBeUndefined();
      expect(store.enabled).toBe(false);
    });
  });

  describe('StoreRequest', () => {
    it('should create a StoreRequest with all fields', () => {
      const request: StoreRequest = {
        storeName: 'New Store',
        email: 'new@store.com',
        phoneNumber: '+525598765432',
        address: {
          street: 'Calle 1',
          streetNumber: '100',
          internalNumber: 'B-2',
          neighborhood: 'Centro',
          zipCode: '06000',
          city: 'CDMX',
          state: 'CDMX',
          countryId: 'MX',
        },
      };

      expect(request.storeName).toBe('New Store');
      expect(request.address?.countryId).toBe('MX');
    });

    it('should create a StoreRequest with only required fields', () => {
      const request: StoreRequest = {
        storeName: 'Minimal Request',
      };

      expect(request.storeName).toBe('Minimal Request');
      expect(request.email).toBeUndefined();
      expect(request.address).toBeUndefined();
    });
  });

  describe('StoreSaveEvent', () => {
    it('should create a StoreSaveEvent for create mode', () => {
      const event: StoreSaveEvent = {
        companyId: 'company-123',
        countryId: 'cc-1',
        regionId: 'region-1',
        zoneId: 'zone-1',
        request: { storeName: 'New Store' },
      };

      expect(event.companyId).toBe('company-123');
      expect(event.zoneId).toBe('zone-1');
      expect(event.storeId).toBeUndefined();
    });

    it('should create a StoreSaveEvent for edit mode with storeId', () => {
      const event: StoreSaveEvent = {
        companyId: 'company-123',
        countryId: 'cc-1',
        regionId: 'region-1',
        zoneId: 'zone-1',
        request: { storeName: 'Updated Store' },
        storeId: 'store-1',
      };

      expect(event.storeId).toBe('store-1');
      expect(event.request.storeName).toBe('Updated Store');
    });
  });

  describe('StoreControl', () => {
    it('should have all FormControl fields with correct types', () => {
      const control: StoreControl = {
        storeName: new FormControl('', { nonNullable: true }),
        email: new FormControl<string | null>(null),
        phoneNumber: new FormControl<string | null>(null),
        address: new FormGroup({
          street: new FormControl<string | null>(null),
          streetNumber: new FormControl<string | null>(null),
          internalNumber: new FormControl<string | null>(null),
          neighborhood: new FormControl<string | null>(null),
          zipCode: new FormControl<string | null>(null),
          city: new FormControl<string | null>(null),
          state: new FormControl<string | null>(null),
          countryId: new FormControl<string | null>(null),
        }),
        enabled: new FormControl(true, { nonNullable: true }),
      };

      expect(control.storeName.value).toBe('');
      expect(control.enabled.value).toBe(true);
      expect(control.email.value).toBeNull();
      expect(control.address.get('countryId')?.value).toBeNull();
    });

    it('should allow setting values on controls', () => {
      const control: StoreControl = {
        storeName: new FormControl('Test Store', { nonNullable: true }),
        email: new FormControl<string | null>('test@test.com'),
        phoneNumber: new FormControl<string | null>(null),
        address: new FormGroup({
          street: new FormControl<string | null>('Main St'),
          streetNumber: new FormControl<string | null>('123'),
          internalNumber: new FormControl<string | null>(null),
          neighborhood: new FormControl<string | null>(null),
          zipCode: new FormControl<string | null>('12345'),
          city: new FormControl<string | null>('Test City'),
          state: new FormControl<string | null>('TS'),
          countryId: new FormControl<string | null>('US'),
        }),
        enabled: new FormControl(false, { nonNullable: true }),
      };

      expect(control.storeName.value).toBe('Test Store');
      expect(control.email.value).toBe('test@test.com');
      expect(control.enabled.value).toBe(false);
    });
  });
});
