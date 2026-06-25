import { FormControl } from '@angular/forms';

/** Matches AddressResponse from backend (nested JSON) */
export interface AddressValue {
  id?: string;
  street?: string | null;
  streetNumber?: string | null;
  internalNumber?: string | null;
  neighborhood?: string | null;
  zipCode?: string | null;
  city?: string | null;
  state?: string | null;
  countryId?: string | null;
}

/** Matches AddressRequest sent to backend */
export interface AddressRequest {
  street?: string | null;
  streetNumber?: string | null;
  internalNumber?: string | null;
  neighborhood?: string | null;
  zipCode?: string | null;
  city?: string | null;
  state?: string | null;
  countryId?: string | null;
}

/** Typed FormGroup controls for AddressFormComponent */
export interface AddressControl {
  street: FormControl<string | null>;
  streetNumber: FormControl<string | null>;
  internalNumber: FormControl<string | null>;
  neighborhood: FormControl<string | null>;
  zipCode: FormControl<string | null>;
  city: FormControl<string | null>;
  state: FormControl<string | null>;
  countryId: FormControl<string | null>;
}
