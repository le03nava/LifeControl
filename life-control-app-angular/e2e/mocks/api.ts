import type { Page, Route } from '@playwright/test';

const API_BASE = process.env.E2E_API_URL || 'http://localhost:9000';

const corsHeaders = (origin: string | undefined): Record<string, string> =>
  origin
    ? {
        'access-control-allow-origin': origin,
        'access-control-allow-credentials': 'true',
        'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS',
        'access-control-allow-headers': 'authorization,content-type',
        vary: 'Origin',
      }
    : {};

export interface CompanyMock {
  id: string;
  companyKey: string;
  companyName: string;
  tipoPersonaId: number;
  razonSocial: string;
  rfc: string;
  email: string;
  phone: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CountryMock {
  id: string;
  countryCode: string;
  countryName: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PageMock<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

const SAMPLE_COMPANIES: CompanyMock[] = [
  {
    id: 'company-1',
    companyKey: 'ACME',
    companyName: 'Acme Corp',
    tipoPersonaId: 2,
    razonSocial: 'Acme Corp, S.A. de C.V.',
    rfc: 'ACM200101010',
    email: 'contacto@acme.example',
    phone: '+525512345678',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'company-2',
    companyKey: 'LOGINOR',
    companyName: 'Logística Norte',
    tipoPersonaId: 1,
    razonSocial: 'Logística Norte',
    rfc: 'LOG980101012',
    email: 'hola@log-norte.example',
    phone: '+525598765432',
    enabled: false,
    createdAt: '2026-02-01T00:00:00Z',
    updatedAt: '2026-02-01T00:00:00Z',
  },
];

const SAMPLE_COUNTRIES: CountryMock[] = [
  {
    id: 'mx',
    countryCode: 'MX',
    countryName: 'México',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'us',
    countryCode: 'US',
    countryName: 'Estados Unidos',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

export interface ApiMockOptions {
  companies?: CompanyMock[];
  countries?: CountryMock[];
}

/**
 * Mocks the API Gateway (localhost:9000/api/...) with an in-memory store.
 * Company CRUD is stateful so the create → edit flow reflects the created record.
 */
export async function installApiMock(page: Page, options: ApiMockOptions = {}): Promise<void> {
  const companies: CompanyMock[] = options.companies
    ? [...options.companies]
    : [...SAMPLE_COMPANIES];
  const countries: CountryMock[] = options.countries
    ? [...options.countries]
    : [...SAMPLE_COUNTRIES];
  let nextId = 1;

  await page.route(`${API_BASE}/**`, async (route) => {
    const request = route.request();
    const method = request.method();
    const cors = corsHeaders(request.headers()['origin']);

    if (method === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors, body: '' });
      return;
    }

    const path = new URL(request.url()).pathname;

    if (path === '/api/companies' && method === 'GET') {
      const url = new URL(request.url());
      const page = pageResponse(filterCompanies(companies, url), url);
      await fulfillJson(route, page, cors);
      return;
    }

    if (path === '/api/companies' && method === 'POST') {
      const body = JSON.parse(request.postData() ?? '{}') as CompanyMock;
      const created: CompanyMock = {
        ...body,
        id: `e2e-company-${nextId++}`,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      companies.unshift(created);
      await fulfillJson(route, created, cors, 201);
      return;
    }

    const companyMatch = /^\/api\/companies\/([^/]+)$/.exec(path);
    if (companyMatch) {
      const id = companyMatch[1];
      const index = companies.findIndex((c) => c.id === id);
      if (index === -1) {
        await fulfillJson(route, { status: 404, message: 'Empresa no encontrada' }, cors, 404);
        return;
      }
      if (method === 'GET') {
        await fulfillJson(route, companies[index], cors);
        return;
      }
      if (method === 'PUT') {
        companies[index] = {
          ...companies[index],
          ...(JSON.parse(request.postData() ?? '{}') as CompanyMock),
        };
        await fulfillJson(route, companies[index], cors);
        return;
      }
      if (method === 'DELETE') {
        companies.splice(index, 1);
        await route.fulfill({ status: 204, headers: cors, body: '' });
        return;
      }
    }

    if (path === '/api/countries' && method === 'GET') {
      await fulfillJson(route, countries, cors);
      return;
    }

    await fulfillJson(route, { status: 404, message: 'Not found' }, cors, 404);
  });
}

function filterCompanies(companies: CompanyMock[], url: URL): CompanyMock[] {
  const search = url.searchParams.get('search')?.toLowerCase();
  if (!search) {
    return companies;
  }
  return companies.filter((c) =>
    [c.companyName, c.rfc, c.razonSocial, c.email].some((value) =>
      value.toLowerCase().includes(search),
    ),
  );
}

function pageResponse<T>(content: T[], url: URL): PageMock<T> {
  const page = Math.max(0, Number(url.searchParams.get('page') ?? 0));
  const size = Number(url.searchParams.get('size') ?? Math.max(content.length, 1));
  const start = page * size;
  const slice = content.slice(start, start + size);
  const totalPages = content.length === 0 ? 1 : Math.ceil(content.length / size);
  return {
    content: slice,
    totalElements: content.length,
    totalPages,
    size,
    number: page,
    first: page === 0,
    last: page >= totalPages - 1,
    empty: slice.length === 0,
  };
}

async function fulfillJson(
  route: Route,
  body: unknown,
  cors: Record<string, string>,
  status = 200,
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: cors,
    body: JSON.stringify(body),
  });
}
