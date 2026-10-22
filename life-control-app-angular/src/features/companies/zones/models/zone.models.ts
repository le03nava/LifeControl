import { FormControl } from '@angular/forms';

export interface CompanyZone {
  id: string;
  companyRegionId: string;
  companyCountryId: string;
  companyId: string;
  countryId: string;
  zoneCode: string;
  zoneName: string;
  description?: string;
  displayOrder?: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Body of POST/PUT .../zones.
 *
 * Mirrors CreateCompanyZoneRequest and UpdateCompanyZoneRequest on the API side, which declare
 * exactly these keys. `enabled` is deliberately absent: the zone's state is owned by the server —
 * `createZone` always stores `true`, `updateZone` never reads it — and changes only through DELETE
 * (soft-disable) and PATCH (re-enable) on the resource. An extra key here is rejected with 400
 * before field validation runs.
 */
export interface CompanyZoneRequest {
  zoneCode: string;
  zoneName: string;
  description?: string;
  displayOrder?: number;
}

/**
 * Composite save event emitted by ZonesFormComponent.
 * Bundles selector context (companyId, countryId, regionId) with the form payload.
 * countryId here refers to the CompanyCountry join record ID (companyCountryId).
 */
export interface ZoneSaveEvent {
  companyId: string;
  countryId: string;
  regionId: string;
  request: CompanyZoneRequest;
  /** Populated for edit mode (updateZone), omitted for create mode (addZone). */
  zoneId?: string;
}

/**
 * Typed control map for ZonesFormComponent's self-contained FormGroup.
 */
export interface ZoneControl {
  zoneCode: FormControl<string>;
  zoneName: FormControl<string>;
  description: FormControl<string | null>;
  displayOrder: FormControl<number | null>;
}
