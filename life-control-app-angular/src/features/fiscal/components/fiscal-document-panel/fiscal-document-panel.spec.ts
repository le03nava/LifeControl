import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { CANCELLATION_MOTIVE_LABELS } from '@shared/fiscal/cancellation-motive';
import type { ApiError } from '@shared/models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import type {
  Company,
  CompanySelectorOption,
  Page,
} from '@features/companies/companies/models/company.models';
import { CustomerService } from '@features/customers/data/customer.service';
import type { Customer } from '@features/customers/models/customer.models';
import type {
  FiscalCancellationResult,
  FiscalDocument,
  FiscalDocumentCancellation,
  FiscalDocumentIssueResult,
} from '../../data/fiscal-document.models';
import { FiscalDocumentService } from '../../data/fiscal-document.service';
import { FiscalDocumentCancelDialog } from '../fiscal-document-cancel-dialog/fiscal-document-cancel-dialog';
import type { FiscalDocumentCancelDialogData } from '../fiscal-document-cancel-dialog/fiscal-document-cancel-dialog';
import { FISCAL_ISSUABLE_STATUS, FiscalDocumentPanel } from './fiscal-document-panel';

const SALE_ID = 'sale-1';
const CUSTOMER_ID = 'customer-1';
const COMPANY_ID = 'company-1';
const DOCUMENT_ID = 'document-1';
const UUID_FISCAL = '0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0';
const STAMPED_AT = '2026-11-17T10:00:00-06:00';
const CANCELLED_AT = '2026-11-18T09:00:00-06:00';
const CANCELLATION_ID = 'cancellation-1';

/** What `GET /{id}/xml` answers under the `Content-Disposition` the API builds. */
const XML_FILE_NAME = 'Factura A-1234.xml';
const OBJECT_URL = 'blob:fiscal-xml';
/** The stamped bytes: an XML document that must never reach the page as text (`S3`). */
const XML_BODY = '<?xml version="1.0" encoding="UTF-8"?><cfdi:Comprobante Folio="A-1234"/>';

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

/** The cancellation block the read states once a document is cancelled (`L9`). */
function cancellation(
  overrides: Partial<FiscalDocumentCancellation> = {},
): FiscalDocumentCancellation {
  return {
    cancellationDocumentId: CANCELLATION_ID,
    motive: '02',
    cancelledAt: CANCELLED_AT,
    ...overrides,
  };
}

/** The authority granted the cancellation: `201`, and a row was written (`S5`). */
function grantedCancellation(): FiscalCancellationResult {
  return {
    kind: 'granted',
    cancellationDocumentId: CANCELLATION_ID,
    cancelledDocumentId: DOCUMENT_ID,
    motive: '02',
    status: 'CANCELLED',
    statusUuid: '201',
    message: 'Comprobante cancelado.',
  };
}

/** The authority received the request and has not decided: `202`, and nothing was written (`S5`). */
function submittedCancellation(): FiscalCancellationResult {
  return {
    kind: 'submitted',
    cancelledDocumentId: DOCUMENT_ID,
    motive: '02',
    statusUuid: '211',
    message: 'La solicitud de cancelación está en proceso.',
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
  let reprintXml: ReturnType<typeof vi.fn>;
  let getCompanySelector: ReturnType<typeof vi.fn>;
  let getCompanyById: ReturnType<typeof vi.fn>;
  let getCustomerById: ReturnType<typeof vi.fn>;
  /** The spy on the `MatDialog` the panel itself injects; it is installed when the panel is rendered. */
  let openDialog: ReturnType<typeof vi.spyOn>;
  /** What the cancel dialog answers when it closes; `null` is its explicit cancel. */
  let dialogAnswer: FiscalCancellationResult | null;
  let createObjectURL: ReturnType<typeof vi.fn>;
  let revokeObjectURL: ReturnType<typeof vi.fn>;
  /** The order of the download's three steps, so "revoked after the click" is asserted and not assumed. */
  let downloadSteps: string[];
  let clickedAnchors: HTMLAnchorElement[];

  beforeEach(() => {
    readBySale = vi.fn(() => of<FiscalDocument | null>(null));
    issue = vi.fn(() => of(stampAnswer()));
    reprintXml = vi.fn(() =>
      of({ blob: new Blob([XML_BODY], { type: 'application/xml' }), fileName: XML_FILE_NAME }),
    );
    getCompanySelector = vi.fn(() => of(selectorPage()));
    getCompanyById = vi.fn(() => of(issuer()));
    getCustomerById = vi.fn(() => of(receiver()));
    dialogAnswer = null;

    downloadSteps = [];
    clickedAnchors = [];
    createObjectURL = vi.fn(() => {
      downloadSteps.push('create');
      return OBJECT_URL;
    });
    revokeObjectURL = vi.fn(() => {
      downloadSteps.push('revoke');
    });
    // jsdom implements neither half of the object-URL API, and this panel is the tree's only user of it.
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: createObjectURL,
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: revokeObjectURL,
    });

    TestBed.configureTestingModule({
      imports: [FiscalDocumentPanel],
      providers: [
        { provide: FiscalDocumentService, useValue: { readBySale, issue, reprintXml } },
        { provide: CompanyService, useValue: { getCompanySelector, getCompanyById } },
        { provide: CustomerService, useValue: { getCustomerById } },
      ],
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(URL, 'createObjectURL');
    Reflect.deleteProperty(URL, 'revokeObjectURL');
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
    // The panel resolves `MatDialog` from the module it imports, so the spy goes on the instance the
    // component itself injects: a provider override in this module would be shadowed by that one.
    const matDialog = fixture.debugElement.injector.get(MatDialog);
    openDialog = vi.spyOn(matDialog, 'open').mockImplementation(
      () =>
        ({
          afterClosed: () => of(dialogAnswer),
        }) as unknown as MatDialogRef<FiscalDocumentCancelDialog, FiscalCancellationResult | null>,
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

  /** The reprint affordance (`S3`): offered for the stamped document, never before one exists. */
  const downloadButton = (): HTMLButtonElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[data-testid="fiscal-reprint"]',
    );

  /** The cancel affordance (`S4`): offered only while the read states no cancellation. */
  const cancelButton = (): HTMLButtonElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[data-testid="fiscal-cancel"]',
    );

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function clickDownload(): Promise<void> {
    downloadButton()?.click();
    await settle();
  }

  async function clickCancel(): Promise<void> {
    cancelButton()?.click();
    await settle();
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

  describe('the reprint (S3)', () => {
    beforeEach(() => {
      // The anchor never reaches a real download: the click is the step the spec observes, and it sits
      // between the URL being created and being revoked.
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
        this: HTMLAnchorElement,
      ): void {
        clickedAnchors.push(this);
        downloadSteps.push('click');
      });
    });

    it('downloads the document the read answered, by its id', async () => {
      readBySale.mockReturnValue(of(document()));

      await render();
      await clickDownload();

      expect(reprintXml).toHaveBeenCalledTimes(1);
      expect(reprintXml).toHaveBeenCalledWith(DOCUMENT_ID);
    });

    it('hands the bytes to the browser under the served file name and revokes the URL after it', async () => {
      readBySale.mockReturnValue(of(document()));

      await render();
      await clickDownload();

      expect(createObjectURL).toHaveBeenCalledTimes(1);
      expect(clickedAnchors).toHaveLength(1);
      expect(clickedAnchors[0]?.download).toBe(XML_FILE_NAME);
      expect(clickedAnchors[0]?.getAttribute('href')).toBe(OBJECT_URL);
      // The anchor is a hand-off and not a page element: it leaves the document once clicked.
      expect(clickedAnchors[0]?.isConnected).toBe(false);
      expect(revokeObjectURL).toHaveBeenCalledWith(OBJECT_URL);
      expect(downloadSteps).toEqual(['create', 'click', 'revoke']);
    });

    it('shows the API message when the reprint is refused, and hands nothing to the browser', async () => {
      readBySale.mockReturnValue(of(document()));
      reprintXml.mockReturnValue(
        throwError(() => apiFailure(404, 'Fiscal document document-1 has no stamped XML.')),
      );

      await render();
      await clickDownload();

      expect(text()).toContain('Fiscal document document-1 has no stamped XML.');
      expect(createObjectURL).not.toHaveBeenCalled();
      expect(revokeObjectURL).not.toHaveBeenCalled();
      expect(clickedAnchors).toHaveLength(0);
    });

    it('revokes the object URL when the browser refuses the hand-off', async () => {
      readBySale.mockReturnValue(of(document()));
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
        throw new Error('The download was blocked.');
      });

      await render();
      await clickDownload();

      expect(createObjectURL).toHaveBeenCalledTimes(1);
      expect(revokeObjectURL).toHaveBeenCalledWith(OBJECT_URL);
      expect(downloadSteps).toEqual(['create', 'revoke']);
      expect(text()).toContain('No se pudo entregar el archivo al navegador');
    });

    it('never writes the XML into the page as text', async () => {
      readBySale.mockReturnValue(of(document()));

      await render();

      const before = (fixture.nativeElement as HTMLElement).innerHTML;
      expect(before).not.toContain('cfdi:Comprobante');

      await clickDownload();

      const after = (fixture.nativeElement as HTMLElement).innerHTML;
      expect(after).not.toContain('cfdi:Comprobante');
      expect(after).not.toContain('<?xml');
    });

    it('offers no reprint while the sale has no document', async () => {
      await render();

      expect(downloadButton()).toBeNull();
      expect(reprintXml).not.toHaveBeenCalled();
    });
  });

  describe('the cancellation (S4, S5, S6)', () => {
    it('shows the cancellation the read carries, with its motive and date, and no cancel action', async () => {
      readBySale.mockReturnValue(of(document({ cancellation: cancellation() })));

      await render();

      expect(text()).toContain('El documento está cancelado');
      expect(text()).toContain(CANCELLATION_MOTIVE_LABELS['02'] as string);
      expect(text()).toContain(CANCELLED_AT);
      expect(cancelButton()).toBeNull();
      // The stamped bytes survive the cancellation, so the reprint stays available.
      expect(downloadButton()).not.toBeNull();
    });

    it('opens the cancel dialog for the document the read reported', async () => {
      readBySale.mockReturnValue(of(document()));

      await render();
      await clickCancel();

      expect(openDialog).toHaveBeenCalledTimes(1);
      const [component, config] = openDialog.mock.calls[0] as unknown as [
        unknown,
        { data: FiscalDocumentCancelDialogData },
      ];
      expect(component).toBe(FiscalDocumentCancelDialog);
      expect(config.data).toEqual({ documentId: DOCUMENT_ID });
    });

    it('offers no cancel action before a document was read', async () => {
      await render();

      expect(cancelButton()).toBeNull();
      expect(openDialog).not.toHaveBeenCalled();
    });

    it('re-reads the document after a granted cancellation, so the state comes from the API', async () => {
      readBySale
        .mockReturnValueOnce(of(document()))
        .mockReturnValueOnce(of(document({ cancellation: cancellation() })));
      dialogAnswer = grantedCancellation();

      await render();
      await clickCancel();

      expect(readBySale).toHaveBeenCalledTimes(2);
      expect(text()).toContain('El documento está cancelado');
      expect(text()).toContain(CANCELLED_AT);
      expect(cancelButton()).toBeNull();
    });

    it('keeps a submitted cancellation as submitted and not accepted, and does not offer it again', async () => {
      readBySale.mockReturnValue(of(document()));
      dialogAnswer = submittedCancellation();

      await render();
      await clickCancel();

      // Nothing was written, so there is nothing new to read: the 202 is the answer the panel holds.
      expect(readBySale).toHaveBeenCalledTimes(1);
      expect(text()).toContain('La cancelación se envió y todavía no está aceptada');
      expect(text()).toContain('211');
      expect(text()).toContain('sigue vigente');
      expect(text()).not.toContain('El documento está cancelado');
      expect(cancelButton()).toBeNull();
    });

    it('does nothing when the dialog is dismissed', async () => {
      readBySale.mockReturnValue(of(document()));
      dialogAnswer = null;

      await render();
      await clickCancel();

      expect(readBySale).toHaveBeenCalledTimes(1);
      expect(text()).not.toContain('La cancelación se envió');
      expect(cancelButton()).not.toBeNull();
    });
  });
});
