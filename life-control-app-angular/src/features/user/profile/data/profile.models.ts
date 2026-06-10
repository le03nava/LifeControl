/**
 * Response from GET /api/profile
 * Combines basic identity info (from Keycloak) with location preferences (from user_preferences).
 */
export interface ProfileResponse {
  keycloakUserId: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  companyCountryId: string | null;
  companyId: string | null;
  companyRegionId: string | null;
  companyZoneId: string | null;
  companyStoreId: string | null;
}

/**
 * Payload for PUT /api/profile
 * All fields are optional — only provided fields are updated.
 * Location fields can be set to null to clear the preference.
 */
export interface ProfileUpdateRequest {
  firstName?: string;
  lastName?: string;
  email?: string;
  companyCountryId?: string | null;
  companyId?: string | null;
  companyRegionId?: string | null;
  companyZoneId?: string | null;
  companyStoreId?: string | null;
}
