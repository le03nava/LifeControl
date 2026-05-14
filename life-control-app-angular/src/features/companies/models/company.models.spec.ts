import { Country, CompanyCountry, CompanyCountryRequest, Company } from './company.models';

describe('CompanyModels — Country Types', () => {
  it('should allow creating a Country object', () => {
    const country: Country = {
      id: '1',
      countryCode: 'MX',
      countryName: 'Mexico',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    expect(country.countryCode).toBe('MX');
    expect(country.countryName).toBe('Mexico');
    expect(country.enabled).toBe(true);
  });

  it('should allow creating a CompanyCountry with localAlias', () => {
    const cc: CompanyCountry = {
      id: 'cc-1',
      companyId: 'comp-1',
      countryId: '1',
      countryCode: 'MX',
      countryName: 'Mexico',
      localAlias: 'Sucursal CDMX',
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    expect(cc.companyId).toBe('comp-1');
    expect(cc.localAlias).toBe('Sucursal CDMX');
    expect(cc.countryCode).toBe('MX');
  });

  it('should allow CompanyCountry with null localAlias', () => {
    const cc: CompanyCountry = {
      id: 'cc-2',
      companyId: 'comp-1',
      countryId: '2',
      countryCode: 'US',
      countryName: 'United States',
      localAlias: null,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    expect(cc.localAlias).toBeNull();
  });

  it('should allow CompanyCountryRequest without optional localAlias', () => {
    const req: CompanyCountryRequest = { countryCode: 'AR' };
    expect(req.countryCode).toBe('AR');
    expect(req.localAlias).toBeUndefined();
  });

  it('should allow CompanyCountryRequest with localAlias', () => {
    const req: CompanyCountryRequest = {
      countryCode: 'BR',
      localAlias: 'Sucursal SP',
    };
    expect(req.countryCode).toBe('BR');
    expect(req.localAlias).toBe('Sucursal SP');
  });

  it('should allow Company with optional countries field', () => {
    const company: Company = {
      id: '1',
      companyId: 1,
      companyName: 'Test Corp',
      tipoPersonaId: 1,
      razonSocial: 'Test Corp SA',
      rfc: 'XAXX010101000',
      email: 'test@test.com',
      phone: '555-0001',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
      countries: [],
    };
    expect(company.countries).toEqual([]);
  });

  it('should allow Company without countries field', () => {
    const company: Company = {
      id: '2',
      companyId: 2,
      companyName: 'Other Corp',
      tipoPersonaId: 1,
      razonSocial: 'Other Corp SA',
      rfc: 'XAXX010101001',
      email: 'other@test.com',
      phone: '555-0002',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    // countries is optional — company without it should still be valid
    expect(company.companyName).toBe('Other Corp');
  });
});
