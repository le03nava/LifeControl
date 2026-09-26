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
  /**
   * Optimistic-locking version, echoed back on update so a lost update is refused. Required on
   * purpose: if the API stops sending it, `tsc` fails instead of the screen quietly omitting the
   * precondition.
   */
  version: number;
}

export interface StoreRequest {
  storeName: string;
  email?: string;
  phoneNumber?: string;
  address?: AddressRequest;
  /**
   * Optional version precondition. Shared by create and update, so the page decides whether the key
   * is present at all: a create body must serialize no `version` key.
   */
  version?: number;
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
