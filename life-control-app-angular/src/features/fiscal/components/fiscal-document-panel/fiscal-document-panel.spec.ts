import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import type { ApiError } from '@shared/models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import type {
  Company,
  CompanySelectorOption,
  Page,
} from '@features/companies/companies/models/company.models';
import { CustomerService } from '@features/customers/data/customer.service';
import type { Customer } from '@features/customers/models/customer.models';
import type { FiscalDocument, FiscalDocumentIssueResult } from '../../data/fiscal-document.models';
import { FiscalDocumentService } from '../../data/fiscal-document.service';
import { FISCAL_ISSUABLE_STATUS, FiscalDocumentPanel } from './fiscal-document-panel';

const SALE_ID = 'sale-1';
const CUSTOMER_ID = 'customer-1';
const COMPANY_ID = 'company-1';
const DOCUMENT_ID = 'document-1';
const UUID_FISCAL = '0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0';
const STAMPED_AT = '2026-11-17T10:00:00-06:00';

/** The issuer's row, complete: the regime and the place of issue are the two facts a document needs. */
function issuer(overrides: Partial<Company> = {}): Company {
  return {
    id: COMPANY_ID,
    companyKey: 'lifecontrol',
    companyName: 'LifeControl',
    tipoPersonaId: 1,
    razonSocial: 'LifeControl SA de CV',
    rfc: 'LCO010101AAA',
    email: 'facturas@lifecontrol.mx',
    phone: '5555555555',
    currency: 'MXN',
    regimenFiscal: '601',
    fiscalZipCode: '06700',
    enabled: true,
    createdAt: '2026-01-01T00:00:00-06:00',
    updatedAt: '2026-01-01T00:00:00-06:00',
    ...overrides,
  };
}

/** The receiver's row, with all four facts of the profile stated — the shape a document can be issued for. */
function receiver(overrides: Partial<Customer> = {}): Customer {
  return {
    id: CUSTOMER_ID,
    name: 'Cliente Facturable',
    email: 'cliente@example.mx',
    phone: null,
    rfc: 'CFF010101AAA',
    salesChannel: 'RETAIL',
    enabled: true,
    legalName: 'Cliente Facturable SA de CV',
    regimenFiscal: '612',
    fiscalStreet: null,
    fiscalZipCode: '06700',
    fiscalState: null,
    fiscalCountryId: null,
    defaultUsoCfdi: 'G03',
    createdAt: '2026-01-01T00:00:00-06:00',
    updatedAt: '2026-01-01T00:00:00-06:00',
    ...overrides,
  };
}

/** The seller's own row, as the selector read answers it (`GET /api/companies/selector`). */
function selectorPage(): Page<CompanySelectorOption> {
  return {
    content: [{ id: COMPANY_ID, companyKey: 'lifecontrol', companyName: 'LifeControl' }],
    totalElements: 1,
    totalPages: 1,
    size: 1,
    number: 0,
    first: true,
    last: true,
    empty: false,
  };
}

/** A sale's stamped document as the read answers it (`GET /api/fiscal-documents/by-sale/{id}`). */
function document(overrides: Partial<FiscalDocument> = {}): FiscalDocument {
  return {
    documentId: DOCUMENT_ID,
    salesOrderId: SALE_ID,
    status: 'STAMPED',
    uuidFiscal: UUID_FISCAL,
    stampedAt: STAMPED_AT,
    folio: 'A-1234',
    cancellation: null,
    ...overrides,
  };
}

/** What a stamp attempt answers: the document, and whether **this** attempt stamped it. */
function stampAnswer(stampedByThisAttempt = true): FiscalDocumentIssueResult {
  return {
    document: {
      documentId: DOCUMENT_ID,
      salesOrderId: SALE_ID,
      status: 'STAMPED',
      uuidFiscal: UUID_FISCAL,
      stampedAt: STAMPED_AT,
    },
    stampedByThisAttempt,
  };
}

/** The API's own refusal: an `ApiError` body, which is the sentence `S1`'s screen has to show. */
function apiFailure(status: number, message: string): HttpErrorResponse {
  const body: ApiError = {
    status,
    message,
    path: '/api/fiscal-documents',
    timestamp: '2026-11-17T10:00:00-06:00',
    correlationId: 'correlation-1',
  };
  return new HttpErrorResponse({ status, error: body });
}

describe('FiscalDocumentPanel', () => {
  let fixture: ComponentFixture<FiscalDocumentPanel>;
  let readBySale: ReturnType<typeof vi.fn>;
  let issue: ReturnType<typeof vi.fn>;
  let getCompanySelector: ReturnType<typeof vi.fn>;
  let getCompanyById: ReturnType<typeof vi.fn>;
  let getCustomerById: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    readBySale = vi.fn(() => of<FiscalDocument | null>(null));
    issue = vi.fn(() => of(stampAnswer()));
    getCompanySelector = vi.fn(() => of(selectorPage()));
    getCompanyById = vi.fn(() => of(issuer()));
    getCustomerById = vi.fn(() => of(receiver()));

    TestBed.configureTestingModule({
      imports: [FiscalDocumentPanel],
      providers: [
        { provide: FiscalDocumentService, useValue: { readBySale, issue } },
        { provide: CompanyService, useValue: { getCompanySelector, getCompanyById } },
        { provide: CustomerService, useValue: { getCustomerById } },
      ],
    });
  });

  /**
   * Mounts the panel the way the sale page does, with its three inputs set before the first change
   * detection — the panel reads on `ngOnInit`, so an input set afterwards would describe a different sale.
   */
  async function render(
    inputs: { saleStatus?: string; customerId?: string | null } = {},
  ): Promise<void> {
    fixture = TestBed.createComponent(FiscalDocumentPanel);
    fixture.componentRef.setInput('saleId', SALE_ID);
    fixture.componentRef.setInput('saleStatus', inputs.saleStatus ?? FISCAL_ISSUABLE_STATUS);
    fixture.componentRef.setInput(
      'customerId',
      inputs.customerId === undefined ? CUSTOMER_ID : inputs.customerId,
    );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function buttonByText(label: string): HTMLButtonElement | undefined {
    const root = fixture.nativeElement as HTMLElement;
    return Array.from(root.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === label,
    );
  }

  /** The issue affordance, by the hook its own markup carries (its label sits beside an icon). */
  const issueButton = (): HTMLButtonElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[data-testid="fiscal-issue"]',
    );

  /** Clicks the affordance and settles the answer, as an operator's click does. */
  async function clickIssue(): Promise<void> {
    issueButton()?.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  describe('before anything is stamped', () => {
    it('reads the sale and offers the action without issuing anything on init', async () => {
      await render();

      expect(readBySale).toHaveBeenCalledWith(SALE_ID);
      expect(issue).not.toHaveBeenCalled();
      expect(issueButton()).not.toBeNull();
      expect(issueButton()?.disabled).toBe(false);
    });

    it('names the issuer when the company declares no fiscal regime', async () => {
      getCompanyById.mockReturnValue(of(issuer({ regimenFiscal: undefined })));

      await render();

      expect(issueButton()).toBeNull();
      expect(text()).toContain('El emisor no puede emitir el documento');
      expect(text()).toContain('régimen fiscal');
    });

    it('names the receiver when the customer states no fiscal fact at all', async () => {
      getCustomerById.mockReturnValue(
        of(
          receiver({
            legalName: null,
            regimenFiscal: null,
            fiscalZipCode: null,
            defaultUsoCfdi: null,
          }),
        ),
      );

      await render();

      expect(issueButton()).toBeNull();
      expect(text()).toContain('El receptor no puede recibir el documento');
      expect(text()).toContain('ningún dato fiscal');
    });

    it('names the receiver and the fact its half-stated profile lacks', async () => {
      getCustomerById.mockReturnValue(of(receiver({ defaultUsoCfdi: null })));

      await render();

      expect(issueButton()).toBeNull();
      expect(text()).toContain('El receptor no puede recibir el documento');
      expect(text()).toContain('uso de CFDI');
    });

    it('names both sides when neither profile is complete', async () => {
      getCompanyById.mockReturnValue(of(issuer({ fiscalZipCode: undefined })));
      getCustomerById.mockReturnValue(of(receiver({ regimenFiscal: null })));

      await render();

      expect(issueButton()).toBeNull();
      expect(text()).toContain('El emisor no puede emitir el documento');
      expect(text()).toContain('lugar de expedición');
      expect(text()).toContain('El receptor no puede recibir el documento');
    });

    it('names the receiver when the sale states no customer, and reads no customer', async () => {
      await render({ customerId: null });

      expect(getCustomerById).not.toHaveBeenCalled();
      expect(issueButton()).toBeNull();
      expect(text()).toContain('no declara cliente');
    });

    it('offers nothing while the sale is not completed', async () => {
      await render({ saleStatus: 'Pending' });

      expect(issueButton()).toBeNull();
      expect(text()).toContain('todavía no está cerrada');
    });

    it('shows the API message of a failed read and offers the read again instead of issuing', async () => {
      readBySale.mockReturnValue(
        throwError(() => apiFailure(404, 'Sale sale-1 does not exist in this company.')),
      );

      await render();

      expect(text()).toContain('Sale sale-1 does not exist in this company.');
      expect(issueButton()).toBeNull();
      expect(buttonByText('Reintentar')).toBeDefined();
    });
  });

  describe('once the sale has a document', () => {
    it('shows its identity and offers no issue action', async () => {
      readBySale.mockReturnValue(of(document()));

      await render();

      expect(text()).toContain(DOCUMENT_ID);
      expect(text()).toContain(UUID_FISCAL);
      expect(text()).toContain(STAMPED_AT);
      expect(text()).toContain('A-1234');
      expect(issueButton()).toBeNull();
    });

    it('says the document states no folio instead of inventing one', async () => {
      readBySale.mockReturnValue(of(document({ folio: null })));

      await render();

      expect(text()).toContain('Sin folio');
    });
  });

  describe('issuing', () => {
    it('sends the attempt key and reports the document id and its uuid fiscal', async () => {
      await render();

      await clickIssue();

      expect(issue).toHaveBeenCalledTimes(1);
      const [saleArg, attemptKey] = issue.mock.calls[0] as [string, string];
      expect(saleArg).toBe(SALE_ID);
      expect(typeof attemptKey).toBe('string');
      expect(attemptKey.length).toBeGreaterThan(0);

      expect(text()).toContain('Este intento timbró el documento');
      expect(text()).toContain(DOCUMENT_ID);
      expect(text()).toContain(UUID_FISCAL);
      expect(issueButton()).toBeNull();
    });

    it('replays the same attempt key after a failure and shows the API message', async () => {
      issue.mockReturnValueOnce(
        throwError(() =>
          apiFailure(
            409,
            'Sales order sale-1 cannot be invoiced: customers.legal_name is not declared.',
          ),
        ),
      );

      await render();
      await clickIssue();

      expect(text()).toContain(
        'Sales order sale-1 cannot be invoiced: customers.legal_name is not declared.',
      );
      expect(issueButton()).not.toBeNull();
      expect(issueButton()?.disabled).toBe(false);

      issue.mockReturnValue(of(stampAnswer()));
      await clickIssue();

      expect(issue).toHaveBeenCalledTimes(2);
      expect(issue.mock.calls[1]?.[1]).toBe(issue.mock.calls[0]?.[1]);
    });

    it('reports a replay as the same document, never as a second stamp', async () => {
      issue.mockReturnValue(of(stampAnswer(false)));

      await render();
      await clickIssue();

      expect(text()).toContain('no se timbró de nuevo');
      expect(text()).toContain(DOCUMENT_ID);
      expect(text()).toContain(UUID_FISCAL);
      expect(text()).not.toContain('Este intento timbró el documento');
      expect(issueButton()).toBeNull();
    });
  });
});
