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

/** One department as `GET /api/companies/{companyId}/departments` returns it. */
export interface DepartmentMock {
  id: string;
  companyId: string;
  departmentCode: string;
  departmentName: string;
  description: string | null;
  displayOrder: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** One position as `GET /api/companies/{companyId}/positions` returns it. */
export interface PositionMock {
  id: string;
  companyId: string;
  departmentId: string;
  positionCode: string;
  positionName: string;
  description: string | null;
  reportsToPositionId: string | null;
  displayOrder: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** One salary band as `GET …/positions/{positionId}/salary-bands` returns it. */
export interface PositionSalaryBandMock {
  id: string;
  positionId: string;
  seniorityLevelId: string;
  minimumSalary: number;
  maximumSalary: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** One row of a position's role template as `GET …/positions/{id}/roles` returns it. */
export interface PositionRoleMock {
  id: string;
  positionId: string;
  roleName: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** One row of the **global** seniority-level catalog (`/api/seniority-levels`). */
export interface SeniorityLevelMock {
  id: string;
  levelCode: string;
  levelName: string;
  rank: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PurchaseOrderDetailMock {
  id: string;
  purchaseOrderId: string;
  productId: string;
  productName: string;
  productVariantId: string | null;
  productVariantName: string | null;
  quantity: number;
  unitPrice: number;
  total: number;
  receivedQuantity: number;
  comments: string | null;
  statusId: string;
  statusName: string;
  createdAt: string;
  updatedAt: string;
}

export interface PurchaseOrderMock {
  id: string;
  orderNumber: string;
  supplierId: string;
  supplierName: string;
  companyStoreId: string;
  companyStoreName: string;
  companyId: string | null;
  companyCountryId: string | null;
  regionId: string | null;
  zoneId: string | null;
  paymentMethodId: string;
  paymentMethodName: string;
  statusId: string;
  statusName: string;
  comments: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  details: PurchaseOrderDetailMock[];
}

export interface GoodsReceiptLineMock {
  id: string;
  purchaseOrderDetailId: string;
  productVariantId: string;
  quantityReceived: number;
  comments: string | null;
}

export interface GoodsReceiptMock {
  id: string;
  receiptNumber: string;
  purchaseOrderId: string;
  orderNumber: string;
  companyStoreId: string;
  receivingLocationId: string;
  statusId: string;
  statusName: string;
  receivedBy: string | null;
  receivedAt: string;
  comments: string | null;
  enabled: boolean;
  lines: GoodsReceiptLineMock[];
}

export interface StoreLocationMock {
  id: string;
  locationCode: string;
  locationName: string;
  storeZoneId: string;
  zoneCode: string;
  zoneName: string;
  storeAreaId: string;
  areaCode: string;
  areaName: string;
}

export interface StoreInventorySettingsMock {
  companyStoreId: string;
  receivingLocationId: string;
  salesLocationId: string;
}

/** `GET /api/profile`: the identity plus the location preferences the store resolver reads. */
/** The chain the profile's location preference resolves to; `null` when the caller has none. */
export interface ProfileLocationMock {
  companyId: string;
  companyCountryId: string;
  companyRegionId: string;
  companyZoneId: string;
  companyStoreId: string;
  companyStoreName: string;
}

export interface ProfileMock {
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
  /**
   * The location preference `LocationPreference` reads, in the shape the real profile returns it.
   *
   * The flat ids above are not it: the service resolves `profile.location` and nothing else, so a
   * mock without this field reports "no location", every cascade on the app stays unpreselected and
   * every list gated on company, country and region renders its "choose a location" notice.
   */
  location: ProfileLocationMock | null;
}

/** One scheduling activity as `GET /api/scheduling/activities` returns it. */
export interface SchedulingActivityMock {
  id: string;
  companyStoreId: string;
  userId: string | null;
  activityName: string;
  description: string | null;
  durationMinutes: number;
  capacityPerSlot: number;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** One materialized slot as `GET /api/scheduling/slots` returns it (bare array). */
export interface SchedulingSlotMock {
  id: string;
  activityId: string;
  startAt: string;
  endAt: string;
  capacity: number;
  booked: number;
  available: number;
  status: string;
  enabled: boolean;
}

/** One appointment nested inside a `GET /api/scheduling/calendar` entry. */
export interface SchedulingCalendarAppointmentMock {
  id: string;
  userId: string | null;
  customerId: string | null;
  customerName: string | null;
  statusId: string;
  statusName: string;
  notes: string | null;
  enabled: boolean;
}

/** One entry of `GET /api/scheduling/calendar`, which answers a bare array. */
export interface SchedulingCalendarEntryMock {
  slotId: string;
  activityId: string;
  activityName: string;
  activityEnabled: boolean;
  startAt: string;
  endAt: string;
  capacity: number;
  booked: number;
  available: number;
  status: string;
  appointments: SchedulingCalendarAppointmentMock[];
}

/** The `POST /api/scheduling/appointments` response shape. */
export interface SchedulingAppointmentMock {
  id: string;
  slotId: string;
  startAt: string;
  endAt: string;
  activityId: string;
  companyStoreId: string;
  userId: string | null;
  customerId: string | null;
  statusId: string;
  statusName: string;
  notes: string | null;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
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

const SAMPLE_DEPARTMENTS: DepartmentMock[] = [
  {
    id: 'department-1',
    companyId: 'company-1',
    departmentCode: 'OPS',
    departmentName: 'Operaciones',
    description: 'Operaciones de planta',
    displayOrder: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'department-2',
    companyId: 'company-1',
    departmentCode: 'FIN',
    departmentName: 'Finanzas',
    description: null,
    displayOrder: 2,
    enabled: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

const SAMPLE_POSITIONS: PositionMock[] = [
  {
    id: 'position-1',
    companyId: 'company-1',
    departmentId: 'department-1',
    positionCode: 'OPR',
    positionName: 'Operario',
    description: 'Planta',
    reportsToPositionId: null,
    displayOrder: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'position-2',
    companyId: 'company-1',
    departmentId: 'department-1',
    positionCode: 'SUP',
    positionName: 'Supervisor',
    description: null,
    reportsToPositionId: 'position-1',
    displayOrder: 2,
    enabled: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

/**
 * Global seniority-level catalog the salary-band editor joins its rows from, and
 * the bands it starts from. The read returns disabled rows too (D13), so a
 * `enabled: false` band below is a level the last save cleared and kept.
 */
const SAMPLE_SENIORITY_LEVELS: SeniorityLevelMock[] = [
  {
    id: 'seniority-level-1',
    levelCode: 'JR',
    levelName: 'Junior',
    rank: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'seniority-level-2',
    levelCode: 'SSR',
    levelName: 'Semi senior',
    rank: 2,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

const SAMPLE_SALARY_BANDS: PositionSalaryBandMock[] = [
  {
    id: 'salary-band-1',
    positionId: 'position-1',
    seniorityLevelId: 'seniority-level-1',
    minimumSalary: 1000,
    maximumSalary: 1500,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

/**
 * The stored role template of `position-1` and the frozen allowlist the editor
 * draws its checkboxes from: deliberately wider than what is stored, so the
 * screen has roles it can grant but does not grant yet.
 */
const SAMPLE_POSITION_ROLES: PositionRoleMock[] = [
  {
    id: 'position-role-1',
    positionId: 'position-1',
    roleName: 'lc-sales',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'position-role-2',
    positionId: 'position-1',
    roleName: 'lc-company',
    enabled: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

const SAMPLE_GRANTABLE_ROLES = ['lc-company', 'lc-sales', 'lc-scheduling'];

/** Store chain the purchase orders and the inventory endpoints hang from. */
const STORE_CHAIN = {
  companyId: 'company-1',
  companyCountryId: 'company-country-1',
  regionId: 'region-1',
  zoneId: 'zone-1',
  storeId: 'store-1',
};

/**
 * The four reads the location cascade makes, hanging from the same chain the profile names.
 *
 * Both purchase lists gate their table on company, country and region, and they pre-select those
 * levels from the profile only when each id shows up in its own level's options. Without these
 * fixtures the cascade resolved to empty options, no level was pre-selected and the lists rendered
 * their "choose a location" notice instead of their rows — which is what an E2E opening a gated list
 * needs to be true. A single chain is enough: no spec asks for a second company's hierarchy.
 */
const COMPANY_CHAIN_CREATED_AT = '2026-01-01T00:00:00Z';

const SAMPLE_COMPANY_COUNTRIES = [
  {
    id: STORE_CHAIN.companyCountryId,
    companyId: STORE_CHAIN.companyId,
    countryId: 'country-1',
    countryCode: 'MX',
    countryName: 'México',
    localAlias: null,
    createdAt: COMPANY_CHAIN_CREATED_AT,
    updatedAt: COMPANY_CHAIN_CREATED_AT,
  },
];

const SAMPLE_COMPANY_REGIONS = [
  {
    id: STORE_CHAIN.regionId,
    companyCountryId: STORE_CHAIN.companyCountryId,
    companyId: STORE_CHAIN.companyId,
    countryId: 'country-1',
    regionCode: 'CENTRO',
    regionName: 'Centro',
    enabled: true,
    createdAt: COMPANY_CHAIN_CREATED_AT,
    updatedAt: COMPANY_CHAIN_CREATED_AT,
  },
];

const SAMPLE_COMPANY_ZONES = [
  {
    id: STORE_CHAIN.zoneId,
    companyRegionId: STORE_CHAIN.regionId,
    companyCountryId: STORE_CHAIN.companyCountryId,
    companyId: STORE_CHAIN.companyId,
    countryId: 'country-1',
    zoneCode: 'CENTRO-1',
    zoneName: 'Zona Centro',
    enabled: true,
    createdAt: COMPANY_CHAIN_CREATED_AT,
    updatedAt: COMPANY_CHAIN_CREATED_AT,
  },
];

const SAMPLE_COMPANY_STORES = [
  {
    id: STORE_CHAIN.storeId,
    companyId: STORE_CHAIN.companyId,
    companyCountryId: STORE_CHAIN.companyCountryId,
    regionId: STORE_CHAIN.regionId,
    zoneId: STORE_CHAIN.zoneId,
    storeName: 'Sucursal Centro',
    enabled: true,
    createdAt: COMPANY_CHAIN_CREATED_AT,
    updatedAt: COMPANY_CHAIN_CREATED_AT,
  },
];

/** Username the Keycloak mock signs tokens with; used when no bearer token is present. */
const DEFAULT_MOCK_USERNAME = 'e2e-admin';

const SAMPLE_PURCHASE_ORDERS: PurchaseOrderMock[] = [
  {
    id: 'po-1',
    orderNumber: 'OC-2026-001',
    supplierId: 'supplier-1',
    supplierName: 'Distribuidora del Norte',
    companyStoreId: STORE_CHAIN.storeId,
    companyStoreName: 'Sucursal Centro',
    companyId: STORE_CHAIN.companyId,
    companyCountryId: STORE_CHAIN.companyCountryId,
    regionId: STORE_CHAIN.regionId,
    zoneId: STORE_CHAIN.zoneId,
    paymentMethodId: 'payment-method-1',
    paymentMethodName: 'Transferencia bancaria',
    statusId: 'status-accepted',
    statusName: 'Accepted',
    comments: null,
    enabled: true,
    createdAt: '2026-02-10T14:30:00Z',
    updatedAt: '2026-02-12T09:15:00Z',
    details: [
      {
        id: 'po-detail-1',
        purchaseOrderId: 'po-1',
        productId: 'product-1',
        productName: 'Café Molido 1kg',
        productVariantId: 'variant-1',
        productVariantName: 'Molido',
        quantity: 10,
        unitPrice: 120,
        total: 1200,
        receivedQuantity: 0,
        comments: null,
        statusId: 'detail-status-in-transit',
        statusName: 'In Transit',
        createdAt: '2026-02-10T14:30:00Z',
        updatedAt: '2026-02-12T09:15:00Z',
      },
      {
        id: 'po-detail-2',
        purchaseOrderId: 'po-1',
        productId: 'product-2',
        productName: 'Azúcar Refinada 1kg',
        productVariantId: 'variant-2',
        productVariantName: null,
        quantity: 5,
        unitPrice: 45,
        total: 225,
        receivedQuantity: 5,
        comments: null,
        statusId: 'detail-status-received',
        statusName: 'Received',
        createdAt: '2026-02-10T14:30:00Z',
        updatedAt: '2026-02-12T09:15:00Z',
      },
    ],
  },
  {
    id: 'po-2',
    orderNumber: 'OC-2026-002',
    supplierId: 'supplier-2',
    supplierName: 'Lácteos del Valle',
    companyStoreId: STORE_CHAIN.storeId,
    companyStoreName: 'Sucursal Centro',
    companyId: STORE_CHAIN.companyId,
    companyCountryId: STORE_CHAIN.companyCountryId,
    regionId: STORE_CHAIN.regionId,
    zoneId: STORE_CHAIN.zoneId,
    paymentMethodId: 'payment-method-2',
    paymentMethodName: 'Efectivo',
    statusId: 'status-draft',
    statusName: 'Draft',
    comments: null,
    enabled: true,
    createdAt: '2026-02-14T10:00:00Z',
    updatedAt: '2026-02-14T10:00:00Z',
    details: [],
  },
];

/** Goods receipts start empty: the happy path registers the first one. */
const SAMPLE_GOODS_RECEIPTS: GoodsReceiptMock[] = [];

const SAMPLE_STORE_LOCATIONS: StoreLocationMock[] = [
  {
    id: 'location-1',
    locationCode: 'ALM-01',
    locationName: 'Almacén Principal',
    storeZoneId: STORE_CHAIN.zoneId,
    zoneCode: 'ZN-01',
    zoneName: 'Zona Norte',
    storeAreaId: 'area-1',
    areaCode: 'AR-01',
    areaName: 'Área de Recepción',
  },
  {
    id: 'location-2',
    locationCode: 'PISO-01',
    locationName: 'Piso de Venta',
    storeZoneId: STORE_CHAIN.zoneId,
    zoneCode: 'ZN-01',
    zoneName: 'Zona Norte',
    storeAreaId: 'area-2',
    areaCode: 'AR-02',
    areaName: 'Sala de Ventas',
  },
];

const SAMPLE_INVENTORY_SETTINGS: StoreInventorySettingsMock = {
  companyStoreId: STORE_CHAIN.storeId,
  receivingLocationId: 'location-1',
  salesLocationId: 'location-2',
};

/**
 * The authenticated user's profile.
 *
 * `companyStoreId` is the store every scheduling screen falls back to: the header
 * entry carries no `?storeId=`, so the page resolves the store from this read.
 */
const SAMPLE_PROFILE: ProfileMock = {
  keycloakUserId: 'e2e-keycloak-user',
  username: DEFAULT_MOCK_USERNAME,
  email: 'e2e-admin@example.com',
  firstName: 'E2E',
  lastName: 'User',
  companyCountryId: STORE_CHAIN.companyCountryId,
  companyId: STORE_CHAIN.companyId,
  companyRegionId: STORE_CHAIN.regionId,
  companyZoneId: STORE_CHAIN.zoneId,
  companyStoreId: STORE_CHAIN.storeId,
  location: {
    companyId: STORE_CHAIN.companyId,
    companyCountryId: STORE_CHAIN.companyCountryId,
    companyRegionId: STORE_CHAIN.regionId,
    companyZoneId: STORE_CHAIN.zoneId,
    companyStoreId: STORE_CHAIN.storeId,
    companyStoreName: 'Sucursal Centro',
  },
};

/** The store's enabled activity catalogue, the source of the week's slot names. */
const SAMPLE_SCHEDULING_ACTIVITIES: SchedulingActivityMock[] = [
  {
    id: 'e2e-activity-1',
    companyStoreId: STORE_CHAIN.storeId,
    // `null` on purpose: the booking dialog prefills the responsable field from the
    // activity's `userId`, and the spec submits the dialog untouched expecting `null`.
    userId: null,
    activityName: 'Corte de cabello',
    description: null,
    durationMinutes: 30,
    capacityPerSlot: 3,
    enabled: true,
    version: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'e2e-activity-2',
    companyStoreId: STORE_CHAIN.storeId,
    userId: null,
    activityName: 'Barba',
    description: null,
    durationMinutes: 30,
    capacityPerSlot: 2,
    enabled: true,
    version: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
];

/** One `activity_logs` row as `GET /api/activity-logs` serializes it (S3). */
export interface ActivityLogMock {
  id: string;
  userId: string | null;
  username: string | null;
  process: string;
  event: string;
  httpMethod: string;
  httpStatus: number;
  requestPath: string;
  ipAddress: string | null;
  userAgent: string | null;
  payloadJson: string | null;
  createdAt: string;
}

/**
 * Two audit rows for the `Reportes` screen: one with a resolved username and one
 * that outlived its account (`username` and `userId` both `null`), so the actor
 * fallback to an em dash is rendered by the mock too.
 */
const SAMPLE_ACTIVITY_LOGS: ActivityLogMock[] = [
  {
    id: 'e2e-activity-log-1',
    userId: 'e2e-user-ana',
    username: 'ana.gomez',
    process: 'HR',
    event: 'CREATE',
    httpMethod: 'POST',
    httpStatus: 201,
    requestPath: '/api/companies/e2e-company-1/employees',
    ipAddress: '10.0.0.7',
    userAgent: 'playwright',
    payloadJson: null,
    createdAt: '2026-01-02T09:15:00',
  },
  {
    id: 'e2e-activity-log-2',
    userId: null,
    username: null,
    process: 'GOODSRECEIPT',
    event: 'CREATE',
    httpMethod: 'POST',
    httpStatus: 409,
    requestPath: '/api/goods-receipts',
    ipAddress: '10.0.0.8',
    userAgent: 'playwright',
    payloadJson: null,
    createdAt: '2026-01-02T10:30:00',
  },
];

export interface ApiMockOptions {
  companies?: CompanyMock[];
  countries?: CountryMock[];
  purchaseOrders?: PurchaseOrderMock[];
  goodsReceipts?: GoodsReceiptMock[];
  storeLocations?: StoreLocationMock[];
  /** Store inventory settings; `null` makes the endpoint answer 404 (store not configured). */
  inventorySettings?: StoreInventorySettingsMock | null;
  /** Authenticated user's profile; its `companyStoreId` resolves the scheduling store. */
  profile?: ProfileMock;
  /** Scheduling activity catalogue; the calendar's slots are derived from the request range. */
  schedulingActivities?: SchedulingActivityMock[];
  /** Company-scoped department catalogue; the list filter is server-side on `includeDisabled`. */
  departments?: DepartmentMock[];
  /** Company-scoped position catalogue; both filters are server-side. */
  positions?: PositionMock[];
  /** A position's salary bands; both reads and the full-set save are stateful. */
  salaryBands?: PositionSalaryBandMock[];
  /** The global seniority-level catalogue the band editor joins its rows from. */
  seniorityLevels?: SeniorityLevelMock[];
  /** A position's stored role template; the reads and the full-set save are stateful. */
  positionRoles?: PositionRoleMock[];
  /** The allowlist the roles editor draws its checkboxes from. */
  grantableRoles?: string[];
  /** Audit-trail rows behind the admin-only `Reportes` screen. */
  activityLogs?: ActivityLogMock[];
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
  const purchaseOrders: PurchaseOrderMock[] = options.purchaseOrders
    ? [...options.purchaseOrders]
    : [...SAMPLE_PURCHASE_ORDERS];
  const goodsReceipts: GoodsReceiptMock[] = options.goodsReceipts
    ? [...options.goodsReceipts]
    : [...SAMPLE_GOODS_RECEIPTS];
  const activityLogs: ActivityLogMock[] = options.activityLogs
    ? [...options.activityLogs]
    : [...SAMPLE_ACTIVITY_LOGS];
  const storeLocations: StoreLocationMock[] = options.storeLocations
    ? [...options.storeLocations]
    : [...SAMPLE_STORE_LOCATIONS];
  const inventorySettings: StoreInventorySettingsMock | null =
    options.inventorySettings === undefined ? SAMPLE_INVENTORY_SETTINGS : options.inventorySettings;
  const profile: ProfileMock = options.profile ? { ...options.profile } : { ...SAMPLE_PROFILE };
  const schedulingActivities: SchedulingActivityMock[] = options.schedulingActivities
    ? [...options.schedulingActivities]
    : [...SAMPLE_SCHEDULING_ACTIVITIES];
  const departments: DepartmentMock[] = options.departments
    ? [...options.departments]
    : [...SAMPLE_DEPARTMENTS];
  const positions: PositionMock[] = options.positions
    ? [...options.positions]
    : [...SAMPLE_POSITIONS];
  const seniorityLevels: SeniorityLevelMock[] = options.seniorityLevels
    ? [...options.seniorityLevels]
    : [...SAMPLE_SENIORITY_LEVELS];
  const salaryBands: PositionSalaryBandMock[] = options.salaryBands
    ? [...options.salaryBands]
    : [...SAMPLE_SALARY_BANDS];
  const positionRoles: PositionRoleMock[] = options.positionRoles
    ? [...options.positionRoles]
    : [...SAMPLE_POSITION_ROLES];
  const grantableRoles: string[] = options.grantableRoles
    ? [...options.grantableRoles]
    : [...SAMPLE_GRANTABLE_ROLES];
  const rolesOf = (positionId: string): PositionRoleMock[] =>
    positionRoles.filter((role) => role.positionId === positionId);
  /** The band editor's rows are ordered by the level's rank, as the API orders them. */
  const rankOf = (levelId: string): number =>
    seniorityLevels.find((level) => level.id === levelId)?.rank ?? 0;
  const bandsOf = (positionId: string): PositionSalaryBandMock[] =>
    salaryBands
      .filter((band) => band.positionId === positionId)
      .sort((a, b) => rankOf(a.seniorityLevelId) - rankOf(b.seniorityLevelId));
  // Per-test state: the base slots derive from the requested week, so what a booking
  // changes is the extra `booked` count and the appointments it appends. Both live
  // here so the week re-read after a booking renders the new numbers.
  const schedulingAppointments = new Map<string, SchedulingCalendarAppointmentMock[]>();
  const lastCalendarBase = new Map<string, SchedulingCalendarEntryMock>();
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

    // The location cascade's four reads. They come before the by-id pattern below, and `/selector`
    // before it too, because `^/api/companies/([^/]+)$` would otherwise swallow both.
    if (path === '/api/companies/selector' && method === 'GET') {
      const url = new URL(request.url());
      const options = companies.map(({ id, companyKey, companyName }) => ({ id, companyKey, companyName }));
      await fulfillJson(route, pageResponse(options, url), cors);
      return;
    }

    if (/^\/api\/companies\/[^/]+\/countries$/.test(path) && method === 'GET') {
      await fulfillJson(route, SAMPLE_COMPANY_COUNTRIES, cors);
      return;
    }

    if (/^\/api\/companies\/[^/]+\/countries\/[^/]+\/regions$/.test(path) && method === 'GET') {
      await fulfillJson(route, SAMPLE_COMPANY_REGIONS, cors);
      return;
    }

    if (/^\/api\/companies\/[^/]+\/countries\/[^/]+\/regions\/[^/]+\/zones$/.test(path) && method === 'GET') {
      await fulfillJson(route, SAMPLE_COMPANY_ZONES, cors);
      return;
    }

    if (
      /^\/api\/companies\/[^/]+\/countries\/[^/]+\/regions\/[^/]+\/zones\/[^/]+\/stores$/.test(path) &&
      method === 'GET'
    ) {
      await fulfillJson(route, SAMPLE_COMPANY_STORES, cors);
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

    if (path === '/api/purchase-orders' && method === 'GET') {
      const url = new URL(request.url());
      // The list endpoint never embeds the detail lines; the single-order read does.
      const rows = filterPurchaseOrders(purchaseOrders, url).map((order) => ({
        ...order,
        details: [],
      }));
      await fulfillJson(route, pageResponse(rows, url), cors);
      return;
    }

    const purchaseOrderMatch = /^\/api\/purchase-orders\/([^/]+)$/.exec(path);
    if (purchaseOrderMatch && method === 'GET') {
      const orderId = purchaseOrderMatch[1];
      const order = purchaseOrders.find((item) => item.id === orderId);
      if (!order) {
        await fulfillJson(
          route,
          { status: 404, message: `Orden de compra no encontrada: ${orderId}` },
          cors,
          404,
        );
        return;
      }
      await fulfillJson(route, order, cors);
      return;
    }

    if (path === '/api/goods-receipts' && method === 'GET') {
      const url = new URL(request.url());
      await fulfillJson(route, pageResponse(filterReceipts(goodsReceipts, url), url), cors);
      return;
    }

    if (path === '/api/goods-receipts' && method === 'POST') {
      const body = JSON.parse(request.postData() ?? '{}') as {
        purchaseOrderId: string;
        receivingLocationId?: string | null;
        comments?: string | null;
        lines: {
          purchaseOrderDetailId: string;
          quantityReceived: number;
          comments?: string | null;
        }[];
      };
      const order = purchaseOrders.find((item) => item.id === body.purchaseOrderId);
      if (!order) {
        await fulfillJson(
          route,
          { status: 404, message: `Orden de compra no encontrada: ${body.purchaseOrderId}` },
          cors,
          404,
        );
        return;
      }

      const id = `e2e-receipt-${nextId++}`;
      const created: GoodsReceiptMock = {
        id,
        receiptNumber: `GR-${order.orderNumber}-01`,
        purchaseOrderId: order.id,
        orderNumber: order.orderNumber,
        companyStoreId: order.companyStoreId,
        // `null` on the request means "use the store's configured receiving location".
        receivingLocationId:
          body.receivingLocationId ??
          inventorySettings?.receivingLocationId ??
          storeLocations[0]?.id ??
          '',
        statusId: 'status-registered',
        statusName: 'Registered',
        receivedBy: usernameFromAuthHeader(request.headers()['authorization']),
        // `GoodsReceiptResponse.receivedAt` is a zone-less `LocalDateTime`, so the
        // fixture keeps the same shape the real API serializes (no trailing `Z`).
        receivedAt: new Date().toISOString().slice(0, 19),
        comments: body.comments ?? null,
        enabled: true,
        lines: body.lines.map((line, index) => ({
          id: `${id}-line-${index + 1}`,
          purchaseOrderDetailId: line.purchaseOrderDetailId,
          productVariantId:
            order.details.find((detail) => detail.id === line.purchaseOrderDetailId)
              ?.productVariantId ?? '',
          quantityReceived: line.quantityReceived,
          comments: line.comments ?? null,
        })),
      };
      goodsReceipts.unshift(created);
      await fulfillJson(route, created, cors, 201);
      return;
    }

    const receiptMatch = /^\/api\/goods-receipts\/([^/]+)$/.exec(path);
    if (receiptMatch && method === 'GET') {
      const receiptId = receiptMatch[1];
      const receipt = goodsReceipts.find((item) => item.id === receiptId);
      if (!receipt) {
        await fulfillJson(
          route,
          { status: 404, message: `Recibo no encontrado: ${receiptId}` },
          cors,
          404,
        );
        return;
      }
      await fulfillJson(route, receipt, cors);
      return;
    }

    if (
      /^\/api\/companies\/[^/]+\/countries\/[^/]+\/regions\/[^/]+\/zones\/[^/]+\/stores\/[^/]+\/store-locations$/.test(
        path,
      ) &&
      method === 'GET'
    ) {
      await fulfillJson(route, storeLocations, cors);
      return;
    }

    const inventorySettingsMatch =
      /^\/api\/companies\/[^/]+\/countries\/[^/]+\/regions\/[^/]+\/zones\/[^/]+\/stores\/([^/]+)\/inventory-settings$/.exec(
        path,
      );
    if (inventorySettingsMatch && method === 'GET') {
      if (inventorySettings) {
        await fulfillJson(route, inventorySettings, cors);
      } else {
        await fulfillJson(
          route,
          {
            status: 404,
            message: `Store inventory settings not found for store id: ${inventorySettingsMatch[1]}`,
          },
          cors,
          404,
        );
      }
      return;
    }

    if (path === '/api/profile' && method === 'GET') {
      await fulfillJson(route, profile, cors);
      return;
    }

    // Department catalogue: stateful so create / edit / disable reflect back.
    const departmentsRootMatch = /^\/api\/companies\/([^/]+)\/departments$/.exec(path);
    if (departmentsRootMatch) {
      const companyId = departmentsRootMatch[1];
      if (method === 'GET') {
        const url = new URL(request.url());
        const includeDisabled = url.searchParams.get('includeDisabled') === 'true';
        const rows = departments.filter(
          (department) =>
            department.companyId === companyId && (includeDisabled || department.enabled),
        );
        await fulfillJson(route, rows, cors);
        return;
      }
      if (method === 'POST') {
        const body = JSON.parse(request.postData() ?? '{}') as Partial<DepartmentMock>;
        const now = new Date().toISOString();
        const created: DepartmentMock = {
          id: `e2e-department-${nextId++}`,
          companyId,
          departmentCode: body.departmentCode ?? '',
          departmentName: body.departmentName ?? '',
          description: body.description ?? null,
          displayOrder: body.displayOrder ?? null,
          enabled: body.enabled ?? true,
          createdAt: now,
          updatedAt: now,
        };
        departments.push(created);
        await fulfillJson(route, created, cors, 201);
        return;
      }
    }

    const departmentItemMatch = /^\/api\/companies\/([^/]+)\/departments\/([^/]+)$/.exec(path);
    if (departmentItemMatch) {
      const companyId = departmentItemMatch[1];
      const id = departmentItemMatch[2];
      const index = departments.findIndex((d) => d.id === id && d.companyId === companyId);
      if (index === -1) {
        await fulfillJson(route, { status: 404, message: 'Departamento no encontrado' }, cors, 404);
        return;
      }
      if (method === 'GET') {
        await fulfillJson(route, departments[index], cors);
        return;
      }
      if (method === 'PUT') {
        departments[index] = {
          ...departments[index],
          ...(JSON.parse(request.postData() ?? '{}') as Partial<DepartmentMock>),
          updatedAt: new Date().toISOString(),
        };
        await fulfillJson(route, departments[index], cors);
        return;
      }
      if (method === 'DELETE') {
        // Soft delete: the row is kept with `enabled` false.
        departments[index] = { ...departments[index], enabled: false };
        await route.fulfill({ status: 204, headers: cors, body: '' });
        return;
      }
    }

    const departmentEnableMatch = /^\/api\/companies\/([^/]+)\/departments\/([^/]+)\/enable$/.exec(
      path,
    );
    if (departmentEnableMatch && method === 'PATCH') {
      const companyId = departmentEnableMatch[1];
      const id = departmentEnableMatch[2];
      const index = departments.findIndex((d) => d.id === id && d.companyId === companyId);
      if (index === -1) {
        await fulfillJson(route, { status: 404, message: 'Departamento no encontrado' }, cors, 404);
        return;
      }
      departments[index] = {
        ...departments[index],
        enabled: true,
        updatedAt: new Date().toISOString(),
      };
      await fulfillJson(route, departments[index], cors);
      return;
    }

    // Position catalogue: stateful so create / edit / disable reflect back.
    const positionsRootMatch = /^\/api\/companies\/([^/]+)\/positions$/.exec(path);
    if (positionsRootMatch) {
      const companyId = positionsRootMatch[1];
      if (method === 'GET') {
        const url = new URL(request.url());
        const includeDisabled = url.searchParams.get('includeDisabled') === 'true';
        const departmentId = url.searchParams.get('departmentId');
        const rows = positions.filter(
          (position) =>
            position.companyId === companyId &&
            (!departmentId || position.departmentId === departmentId) &&
            (includeDisabled || position.enabled),
        );
        await fulfillJson(route, rows, cors);
        return;
      }
      if (method === 'POST') {
        const body = JSON.parse(request.postData() ?? '{}') as Partial<PositionMock>;
        const now = new Date().toISOString();
        const created: PositionMock = {
          id: `e2e-position-${nextId++}`,
          companyId,
          departmentId: body.departmentId ?? '',
          positionCode: body.positionCode ?? '',
          positionName: body.positionName ?? '',
          description: body.description ?? null,
          reportsToPositionId: body.reportsToPositionId ?? null,
          displayOrder: body.displayOrder ?? null,
          enabled: body.enabled ?? true,
          createdAt: now,
          updatedAt: now,
        };
        positions.push(created);
        await fulfillJson(route, created, cors, 201);
        return;
      }
    }

    const positionItemMatch = /^\/api\/companies\/([^/]+)\/positions\/([^/]+)$/.exec(path);
    if (positionItemMatch) {
      const companyId = positionItemMatch[1];
      const id = positionItemMatch[2];
      const index = positions.findIndex((p) => p.id === id && p.companyId === companyId);
      if (index === -1) {
        await fulfillJson(route, { status: 404, message: 'Puesto no encontrado' }, cors, 404);
        return;
      }
      if (method === 'GET') {
        await fulfillJson(route, positions[index], cors);
        return;
      }
      if (method === 'PUT') {
        positions[index] = {
          ...positions[index],
          ...(JSON.parse(request.postData() ?? '{}') as Partial<PositionMock>),
          updatedAt: new Date().toISOString(),
        };
        await fulfillJson(route, positions[index], cors);
        return;
      }
      if (method === 'DELETE') {
        // Soft delete: the row is kept with `enabled` false.
        positions[index] = { ...positions[index], enabled: false };
        await route.fulfill({ status: 204, headers: cors, body: '' });
        return;
      }
    }

    const positionEnableMatch = /^\/api\/companies\/([^/]+)\/positions\/([^/]+)\/enable$/.exec(
      path,
    );
    if (positionEnableMatch && method === 'PATCH') {
      const companyId = positionEnableMatch[1];
      const id = positionEnableMatch[2];
      const index = positions.findIndex((p) => p.id === id && p.companyId === companyId);
      if (index === -1) {
        await fulfillJson(route, { status: 404, message: 'Puesto no encontrado' }, cors, 404);
        return;
      }
      positions[index] = {
        ...positions[index],
        enabled: true,
        updatedAt: new Date().toISOString(),
      };
      await fulfillJson(route, positions[index], cors);
      return;
    }

    // Position policy (W3): the global level catalogue the band editor joins its
    // rows from, and the position's bands with the API's full-set save.
    if (path === '/api/seniority-levels' && method === 'GET') {
      const url = new URL(request.url());
      const includeDisabled = url.searchParams.get('includeDisabled') === 'true';
      await fulfillJson(
        route,
        seniorityLevels
          .filter((level) => includeDisabled || level.enabled)
          .sort((a, b) => a.rank - b.rank),
        cors,
      );
      return;
    }

    const salaryBandsMatch = /^\/api\/companies\/([^/]+)\/positions\/([^/]+)\/salary-bands$/.exec(
      path,
    );
    if (salaryBandsMatch) {
      const companyId = salaryBandsMatch[1];
      const positionId = salaryBandsMatch[2];
      if (!positions.some((p) => p.id === positionId && p.companyId === companyId)) {
        await fulfillJson(route, { status: 404, message: 'Puesto no encontrado' }, cors, 404);
        return;
      }

      if (method === 'GET') {
        await fulfillJson(route, bandsOf(positionId), cors);
        return;
      }

      if (method === 'PUT') {
        // Full-set semantics (T10/D13): a submitted key is upserted in place, a
        // stored key the request omits is disabled and **kept**, and the response
        // is the stored set after the save — which is what the editor re-seeds from.
        const body = JSON.parse(request.postData() ?? '{}') as {
          bands?: { seniorityLevelId: string; minimumSalary: number; maximumSalary: number }[];
        };
        const submitted = body.bands ?? [];
        const now = new Date().toISOString();

        for (const stored of salaryBands.filter((band) => band.positionId === positionId)) {
          if (!submitted.some((item) => item.seniorityLevelId === stored.seniorityLevelId)) {
            stored.enabled = false;
            stored.updatedAt = now;
          }
        }
        for (const item of submitted) {
          const stored = salaryBands.find(
            (band) =>
              band.positionId === positionId && band.seniorityLevelId === item.seniorityLevelId,
          );
          if (stored) {
            stored.minimumSalary = item.minimumSalary;
            stored.maximumSalary = item.maximumSalary;
            stored.enabled = true;
            stored.updatedAt = now;
          } else {
            salaryBands.push({
              id: `e2e-salary-band-${nextId++}`,
              positionId,
              seniorityLevelId: item.seniorityLevelId,
              minimumSalary: item.minimumSalary,
              maximumSalary: item.maximumSalary,
              enabled: true,
              createdAt: now,
              updatedAt: now,
            });
          }
        }

        await fulfillJson(route, bandsOf(positionId), cors);
        return;
      }
    }

    const positionRolesMatch = /^\/api\/companies\/([^/]+)\/positions\/([^/]+)\/roles$/.exec(path);
    if (positionRolesMatch) {
      const companyId = positionRolesMatch[1];
      const positionId = positionRolesMatch[2];
      if (!positions.some((p) => p.id === positionId && p.companyId === companyId)) {
        await fulfillJson(route, { status: 404, message: 'Puesto no encontrado' }, cors, 404);
        return;
      }

      if (method === 'GET') {
        await fulfillJson(route, rolesOf(positionId), cors);
        return;
      }

      if (method === 'PUT') {
        // Full-set semantics (T20): a submitted name is upserted, a stored name the
        // request omits is disabled and **kept**, and the response is the stored set.
        const body = JSON.parse(request.postData() ?? '{}') as { roles?: { roleName: string }[] };
        const submitted = body.roles ?? [];
        const now = new Date().toISOString();

        for (const stored of positionRoles.filter((role) => role.positionId === positionId)) {
          if (!submitted.some((item) => item.roleName === stored.roleName)) {
            stored.enabled = false;
            stored.updatedAt = now;
          }
        }
        for (const item of submitted) {
          const stored = positionRoles.find(
            (role) => role.positionId === positionId && role.roleName === item.roleName,
          );
          if (stored) {
            stored.enabled = true;
            stored.updatedAt = now;
          } else {
            positionRoles.push({
              id: `e2e-position-role-${nextId++}`,
              positionId,
              roleName: item.roleName,
              enabled: true,
              createdAt: now,
              updatedAt: now,
            });
          }
        }

        await fulfillJson(route, rolesOf(positionId), cors);
        return;
      }
    }

    const positionGrantableRolesMatch =
      /^\/api\/companies\/([^/]+)\/positions\/([^/]+)\/roles\/grantable$/.exec(path);
    if (positionGrantableRolesMatch && method === 'GET') {
      const companyId = positionGrantableRolesMatch[1];
      const positionId = positionGrantableRolesMatch[2];
      if (!positions.some((p) => p.id === positionId && p.companyId === companyId)) {
        await fulfillJson(route, { status: 404, message: 'Puesto no encontrado' }, cors, 404);
        return;
      }

      await fulfillJson(route, { roles: grantableRoles }, cors);
      return;
    }

    if (path === '/api/scheduling/activities' && method === 'GET') {
      const url = new URL(request.url());
      const storeId = url.searchParams.get('storeId');
      const includeDisabled = url.searchParams.get('includeDisabled') === 'true';
      const rows = schedulingActivities.filter(
        (activity) =>
          (includeDisabled || activity.enabled) &&
          (!storeId || activity.companyStoreId === storeId),
      );
      await fulfillJson(route, pageResponse(rows, url), cors);
      return;
    }

    // The materializing read is called for its side effect and the page discards the
    // response, but it must **never** fail: one 404 here would fail the whole week
    // closed and make every grid assertion moot. Always a 200 bare array.
    if (path === '/api/scheduling/slots' && method === 'GET') {
      const url = new URL(request.url());
      const activityId = url.searchParams.get('activityId') ?? '';
      const from = url.searchParams.get('from') ?? '';
      const to = url.searchParams.get('to') ?? '';
      await fulfillJson(
        route,
        buildSchedulingSlots(activityId, from, to, schedulingAppointments),
        cors,
      );
      return;
    }

    if (path === '/api/scheduling/calendar' && method === 'GET') {
      const url = new URL(request.url());
      const from = url.searchParams.get('from') ?? '';
      const to = url.searchParams.get('to') ?? '';
      const activityId = url.searchParams.get('activityId');
      const baseEntries = buildSchedulingCalendarEntries(from, to, activityId);
      lastCalendarBase.clear();
      for (const entry of baseEntries) {
        lastCalendarBase.set(entry.slotId, entry);
      }
      const entries = baseEntries.map((entry) => {
        const bookedAppointments = schedulingAppointments.get(entry.slotId) ?? [];
        const booked = entry.booked + bookedAppointments.length;
        return {
          ...entry,
          booked,
          available: entry.capacity - booked,
          appointments: [...entry.appointments, ...bookedAppointments],
        };
      });
      await fulfillJson(route, entries, cors);
      return;
    }

    if (path === '/api/scheduling/appointments' && method === 'POST') {
      const body = JSON.parse(request.postData() ?? '{}') as {
        slotId?: string;
        userId?: string | null;
        notes?: string | null;
      };
      const base = body.slotId ? lastCalendarBase.get(body.slotId) : undefined;
      if (!base) {
        await fulfillJson(
          route,
          { status: 404, message: `Scheduling slot not found: ${body.slotId ?? ''}` },
          cors,
          404,
        );
        return;
      }

      // `SchedulingAppointmentResponse` serializes zone-less `LocalDateTime`, so the
      // fixture keeps the same shape the real API answers (no trailing `Z`).
      const createdAt = new Date().toISOString().slice(0, 19);
      const created: SchedulingAppointmentMock = {
        id: `e2e-appointment-${nextId++}`,
        slotId: base.slotId,
        startAt: base.startAt,
        endAt: base.endAt,
        activityId: base.activityId,
        companyStoreId:
          schedulingActivities.find((activity) => activity.id === base.activityId)
            ?.companyStoreId ?? STORE_CHAIN.storeId,
        userId: body.userId ?? null,
        customerId: null,
        statusId: 'status-scheduled',
        statusName: 'Scheduled',
        notes: body.notes ?? null,
        enabled: true,
        version: 0,
        createdAt,
        updatedAt: createdAt,
      };
      const bookedAppointments = schedulingAppointments.get(base.slotId) ?? [];
      schedulingAppointments.set(base.slotId, [
        ...bookedAppointments,
        {
          id: created.id,
          userId: created.userId,
          customerId: null,
          customerName: null,
          statusId: 'status-scheduled',
          statusName: 'Scheduled',
          notes: created.notes,
          enabled: true,
        },
      ]);
      await fulfillJson(route, created, cors, 201);
      return;
    }

    if (path === '/api/activity-logs' && method === 'GET') {
      const url = new URL(request.url());
      await fulfillJson(route, pageResponse(filterActivityLogs(activityLogs, url), url), cors);
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

function filterPurchaseOrders(orders: PurchaseOrderMock[], url: URL): PurchaseOrderMock[] {
  const search = url.searchParams.get('search')?.toLowerCase();
  if (!search) {
    return orders;
  }
  return orders.filter((order) =>
    [order.orderNumber, order.supplierName].some((value) => value.toLowerCase().includes(search)),
  );
}

/**
 * The calendar's two slot facts, derived from the requested range so the spec never
 * depends on the wall clock: the visible week's Monday `00:00` is the `from` bound.
 *
 * Timestamps are zone-less local ISO (`YYYY-MM-DDTHH:mm:ss`, no `Z`, no offset),
 * matching the server's `LocalDateTime` contract. `available` is always
 * `capacity - booked`, exactly as the projection derives it.
 *
 * The first entry is the bookable one (`booked: 0`), the second is full
 * (`booked === capacity`) so the grid renders it without a booking affordance.
 * Both live on the requested range: entry one on the Monday itself, entry two the
 * following day.
 */
function buildSchedulingCalendarEntries(
  from: string,
  to: string,
  activityId: string | null,
): SchedulingCalendarEntryMock[] {
  if (!/^\d{4}-\d{2}-\d{2}T/.test(from) || !/^\d{4}-\d{2}-\d{2}T/.test(to)) {
    return [];
  }

  const firstDay = shiftIsoDate(from, 0);
  const secondDay = shiftIsoDate(from, 1);
  const rangeStart = from.slice(0, 10);
  const rangeEnd = to.slice(0, 10);

  const entries: SchedulingCalendarEntryMock[] = [
    {
      slotId: 'e2e-slot-1',
      activityId: 'e2e-activity-1',
      activityName: 'Corte de cabello',
      activityEnabled: true,
      startAt: `${firstDay}T09:00:00`,
      endAt: `${firstDay}T09:30:00`,
      capacity: 3,
      booked: 0,
      available: 3,
      status: 'Scheduled',
      appointments: [],
    },
    {
      slotId: 'e2e-slot-2',
      activityId: 'e2e-activity-2',
      activityName: 'Barba',
      activityEnabled: true,
      startAt: `${secondDay}T10:00:00`,
      endAt: `${secondDay}T10:30:00`,
      capacity: 2,
      booked: 2,
      available: 0,
      status: 'Scheduled',
      appointments: [
        {
          id: 'e2e-appointment-1',
          userId: 'ana.gomez',
          customerId: null,
          customerName: null,
          statusId: 'status-scheduled',
          statusName: 'Scheduled',
          notes: null,
          enabled: true,
        },
        {
          id: 'e2e-appointment-2',
          userId: null,
          customerId: null,
          customerName: null,
          statusId: 'status-scheduled',
          statusName: 'Scheduled',
          notes: null,
          enabled: true,
        },
      ],
    },
  ];

  return entries.filter((entry) => {
    const date = entry.startAt.slice(0, 10);
    const insideRange = date >= rangeStart && date < rangeEnd;
    const matchesActivity = activityId === null || entry.activityId === activityId;
    return insideRange && matchesActivity;
  });
}

/**
 * The bare-array slots the materializing read answers for one activity.
 *
 * Derived from {@link buildSchedulingCalendarEntries} **and** from the appointments the
 * session has booked, so the two reads never drift: a booking moves `booked`/`available`
 * here exactly as it does in the projection. The page discards this response, but the
 * fixture describes the same slots the projection will render, and a mock that disagreed
 * with itself would fail the first assertion that ever read the slots body.
 */
function buildSchedulingSlots(
  activityId: string,
  from: string,
  to: string,
  bookedAppointments: ReadonlyMap<string, SchedulingCalendarAppointmentMock[]>,
): SchedulingSlotMock[] {
  return buildSchedulingCalendarEntries(from, to, activityId).map((entry) => {
    const booked = entry.booked + (bookedAppointments.get(entry.slotId)?.length ?? 0);
    return {
      id: entry.slotId,
      activityId: entry.activityId,
      startAt: entry.startAt,
      endAt: entry.endAt,
      capacity: entry.capacity,
      booked,
      available: entry.capacity - booked,
      status: entry.status,
      enabled: true,
    };
  });
}

/** `YYYY-MM-DD` a number of days after the date part of a zone-less ISO date-time. */
function shiftIsoDate(value: string, days: number): string {
  const [year, month, day] = value.split('T')[0].split('-').map(Number);
  const shifted = new Date(year, month - 1, day + days);
  const pad = (part: number): string => part.toString().padStart(2, '0');
  return `${shifted.getFullYear()}-${pad(shifted.getMonth() + 1)}-${pad(shifted.getDate())}`;
}

/**
 * Applies the six filters `GET /api/activity-logs` accepts — and no others — so the
 * mock cannot disagree with the endpoint's contract. An absent filter matches
 * everything; `from`/`to` compare the date part of `createdAt`.
 */
function filterActivityLogs(logs: ActivityLogMock[], url: URL): ActivityLogMock[] {
  const from = url.searchParams.get('from');
  const to = url.searchParams.get('to');
  const process = url.searchParams.get('process');
  const event = url.searchParams.get('event');
  const userId = url.searchParams.get('userId');
  const httpMethod = url.searchParams.get('httpMethod');

  return logs.filter((log) => {
    const date = log.createdAt.slice(0, 10);
    return (
      (!from || date >= from) &&
      (!to || date <= to) &&
      (!process || log.process === process) &&
      (!event || log.event === event) &&
      (!userId || log.userId === userId) &&
      (!httpMethod || log.httpMethod === httpMethod)
    );
  });
}

function filterReceipts(receipts: GoodsReceiptMock[], url: URL): GoodsReceiptMock[] {
  const search = url.searchParams.get('search')?.toLowerCase();
  if (!search) {
    return receipts;
  }
  return receipts.filter((receipt) =>
    [receipt.receiptNumber, receipt.orderNumber].some((value) =>
      value.toLowerCase().includes(search),
    ),
  );
}

/**
 * Reads `preferred_username` off the bearer token the app attaches to every API
 * Gateway request, so `receivedBy` reflects the mocked Keycloak user. Falls back
 * to the mock's default username when the header is missing or unreadable.
 */
function usernameFromAuthHeader(authorization: string | undefined): string {
  const token = authorization?.startsWith('Bearer ') ? authorization.slice(7) : undefined;
  const payload = token?.split('.')[1];
  if (!payload) {
    return DEFAULT_MOCK_USERNAME;
  }
  try {
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as {
      preferred_username?: string;
    };
    return claims.preferred_username ?? DEFAULT_MOCK_USERNAME;
  } catch {
    return DEFAULT_MOCK_USERNAME;
  }
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
