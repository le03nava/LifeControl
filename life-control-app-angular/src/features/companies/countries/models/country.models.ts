export interface Country {
  id: string;
  countryCode: string;
  countryName: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CompanyCountry {
  id: string;
  companyId: string;
  countryId: string;
  countryCode: string;
  countryName: string;
  localAlias: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CompanyCountryRequest {
  countryCode: string;
  localAlias?: string;
}
