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

/** Store chain the purchase orders and the inventory endpoints hang from. */
const STORE_CHAIN = {
  companyId: 'company-1',
  companyCountryId: 'company-country-1',
  regionId: 'region-1',
  zoneId: 'zone-1',
  storeId: 'store-1',
};

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

export interface ApiMockOptions {
  companies?: CompanyMock[];
  countries?: CountryMock[];
  purchaseOrders?: PurchaseOrderMock[];
  goodsReceipts?: GoodsReceiptMock[];
  storeLocations?: StoreLocationMock[];
  /** Store inventory settings; `null` makes the endpoint answer 404 (store not configured). */
  inventorySettings?: StoreInventorySettingsMock | null;
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
  const storeLocations: StoreLocationMock[] = options.storeLocations
    ? [...options.storeLocations]
    : [...SAMPLE_STORE_LOCATIONS];
  const inventorySettings: StoreInventorySettingsMock | null =
    options.inventorySettings === undefined ? SAMPLE_INVENTORY_SETTINGS : options.inventorySettings;
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
