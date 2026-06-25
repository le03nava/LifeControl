import { FormControl, FormGroup } from '@angular/forms';
import { AddressControl, AddressRequest, AddressValue } from '@shared/models/address.models';

export interface CompanyStore {
  id: string;
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  storeName: string;
  email?: string;
  phoneNumber?: string;
  address?: AddressValue;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface StoreRequest {
  storeName: string;
  email?: string;
  phoneNumber?: string;
  address?: AddressRequest;
}

/**
 * Composite save event emitted by StoresFormComponent.
 * Bundles selector context (companyId, countryId, regionId, zoneId) with the form payload.
 */
export interface StoreSaveEvent {
  companyId: string;
  countryId: string;
  regionId: string;
  zoneId: string;
  request: StoreRequest;
  /** Populated for edit mode (updateStore), omitted for create mode (addStore). */
  storeId?: string;
}

/**
 * Typed control map for StoresFormComponent's self-contained FormGroup.
 */
export interface StoreControl {
  storeName: FormControl<string>;
  email: FormControl<string | null>;
  phoneNumber: FormControl<string | null>;
  address: FormGroup<AddressControl>;
  enabled: FormControl<boolean>;
}
