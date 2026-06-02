import { FormControl } from '@angular/forms';

export interface CompanyStore {
  id: string;
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  storeName: string;
  email?: string;
  phoneNumber?: string;
  // Address (flattened from backend)
  addressId?: string;
  street?: string;
  streetNumber?: string;
  internalNumber?: string;
  neighborhood?: string;
  zipCode?: string;
  city?: string;
  state?: string;
  countryId?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface StoreRequest {
  storeName: string;
  email?: string;
  phoneNumber?: string;
  street?: string;
  streetNumber?: string;
  internalNumber?: string;
  neighborhood?: string;
  zipCode?: string;
  city?: string;
  state?: string;
  countryId?: string;
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
  street: FormControl<string | null>;
  streetNumber: FormControl<string | null>;
  internalNumber: FormControl<string | null>;
  neighborhood: FormControl<string | null>;
  zipCode: FormControl<string | null>;
  city: FormControl<string | null>;
  state: FormControl<string | null>;
  countryId: FormControl<string | null>;
  enabled: FormControl<boolean>;
}
