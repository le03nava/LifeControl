import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { FiscalDocumentService } from './fiscal-document.service';
import type {
  FiscalCancellationGranted,
  FiscalCancellationSubmitted,
  FiscalDocument,
} from './fiscal-document.models';

const SALES_ORDER_ID = '5e2a5d21-0000-4000-8000-0000000000a1';
const DOCUMENT_ID = '9c1f0a44-0000-4000-8000-0000000000b2';
const CANCELLATION_ID = '3b7d6e10-0000-4000-8000-0000000000c3';
const UUID_FISCAL = 'A1B2C3D4-0000-4000-8000-0000000000D4';
const STAMPED_AT = '2026-11-17T10:00:00-06:00';

function documentBody(overrides: Partial<FiscalDocument> = {}): FiscalDocument {
  return {
    documentId: DOCUMENT_ID,
    salesOrderId: SALES_ORDER_ID,
    status: 'STAMPED',
    uuidFiscal: UUID_FISCAL,
    stampedAt: STAMPED_AT,
    folio: 'F-0001',
    cancellation: null,
    ...overrides,
  };
}

/**
 * The wire body of `POST /api/fiscal-documents` (`FiscalDocumentStamp`), **including** the fields the
 * service deliberately does not carry: the stamp answers the provider's bytes and the tenant, and the
 * result type has neither.
 */
interface StampBody {
  documentId: string;
  companyId: string;
  salesOrderId: string;
  attemptKey: string;
  status: string;
  uuidFiscal: string;
  stampedAt: string;
  xml: string | null;
  stampedByThisAttempt: boolean;
}

function stampBody(overrides: Partial<StampBody> = {}): StampBody {
  return {
    documentId: DOCUMENT_ID,
    companyId: '7f3c2b10-0000-4000-8000-0000000000e5',
    salesOrderId: SALES_ORDER_ID,
    attemptKey: 'attempt-1',
    status: 'STAMPED',
    uuidFiscal: UUID_FISCAL,
    stampedAt: STAMPED_AT,
    xml: '<cfdi:Comprobante/>',
    stampedByThisAttempt: true,
    ...overrides,
  };
}

/** The wire body of `POST /api/fiscal-documents/{id}/cancellation` (`FiscalDocumentCancellation`). */
interface CancellationBody {
  cancellationDocumentId: string | null;
  cancelledDocumentId: string;
  motive: string;
  status: string | null;
  statusUuid: string;
  message: string;
}

function grantedBody(overrides: Partial<CancellationBody> = {}): CancellationBody {
  return {
    cancellationDocumentId: CANCELLATION_ID,
    cancelledDocumentId: DOCUMENT_ID,
    motive: '02',
    status: 'CANCELLED',
    statusUuid: '201',
    message: 'Comprobante cancelado correctamente',
    ...overrides,
  };
}

function submittedBody(overrides: Partial<CancellationBody> = {}): CancellationBody {
  return {
    cancellationDocumentId: null,
    cancelledDocumentId: DOCUMENT_ID,
    motive: '02',
    status: null,
    statusUuid: '211',
    message: 'Solicitud recibida; el receptor aún no la atiende',
    ...overrides,
  };
}

describe('FiscalDocumentService', () => {
  let service: FiscalDocumentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [FiscalDocumentService],
    });
    service = TestBed.inject(FiscalDocumentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('readBySale', () => {
    it('addresses the read by the sale alone and answers the document', async () => {
      const read = firstValueFrom(service.readBySale(SALES_ORDER_ID));

      const request = httpMock.expectOne(`${service.apiUrl}/by-sale/${SALES_ORDER_ID}`);
      expect(request.request.method).toBe('GET');
      request.flush(documentBody());

      await expect(read).resolves.toEqual(documentBody());
    });

    it('maps the 204 of a never-invoiced sale to null instead of an error', async () => {
      const read = firstValueFrom(service.readBySale(SALES_ORDER_ID));

      // `observe: 'response'` is what makes this observable at all: without it Angular treats a 204 as an
      // empty body and the caller cannot tell "no document" from "a document with no fields".
      const request = httpMock.expectOne(`${service.apiUrl}/by-sale/${SALES_ORDER_ID}`);
      request.flush(null, { status: 204, statusText: 'No Content' });

      await expect(read).resolves.toBeNull();
    });

    it('carries the cancellation block of a cancelled document, and never the XML', async () => {
      const read = firstValueFrom(service.readBySale(SALES_ORDER_ID));

      httpMock.expectOne(`${service.apiUrl}/by-sale/${SALES_ORDER_ID}`).flush(
        documentBody({
          cancellation: {
            cancellationDocumentId: CANCELLATION_ID,
            motive: '02',
            cancelledAt: STAMPED_AT,
          },
        }),
      );

      const document = await read;
      expect(document?.cancellation?.cancellationDocumentId).toBe(CANCELLATION_ID);
      expect(document?.cancellation?.motive).toBe('02');
      expect(document && 'xml' in document).toBe(false);
    });
  });

  describe('issue', () => {
    it('posts the sale and the attempt key, and carries the document the attempt stamped', async () => {
      const issued = firstValueFrom(service.issue(SALES_ORDER_ID, 'attempt-1'));

      const request = httpMock.expectOne(service.apiUrl);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toStrictEqual({
        salesOrderId: SALES_ORDER_ID,
        attemptKey: 'attempt-1',
      });
      request.flush(stampBody(), { status: 201, statusText: 'Created' });

      await expect(issued).resolves.toStrictEqual({
        document: {
          documentId: DOCUMENT_ID,
          salesOrderId: SALES_ORDER_ID,
          status: 'STAMPED',
          uuidFiscal: UUID_FISCAL,
          stampedAt: STAMPED_AT,
        },
        stampedByThisAttempt: true,
      });
    });

    it('reports a replay as the same document, not stamped by this attempt', async () => {
      const issued = firstValueFrom(service.issue(SALES_ORDER_ID, 'attempt-1'));

      // A replay is `200` with the row the earlier attempt wrote: same document, nobody called.
      httpMock
        .expectOne(service.apiUrl)
        .flush(stampBody({ stampedByThisAttempt: false }), { status: 200, statusText: 'OK' });

      const result = await issued;
      expect(result.document.documentId).toBe(DOCUMENT_ID);
      expect(result.stampedByThisAttempt).toBe(false);
    });

    it('drops the stamp’s bytes and tenant, which the result type does not carry', async () => {
      const issued = firstValueFrom(service.issue(SALES_ORDER_ID, 'attempt-1'));

      httpMock
        .expectOne(service.apiUrl)
        .flush(stampBody({ xml: null }), { status: 201, statusText: 'Created' });

      const result = await issued;
      expect(result.document).not.toHaveProperty('xml');
      expect(result.document).not.toHaveProperty('companyId');
      expect(result.document).not.toHaveProperty('attemptKey');
    });

    it('lets the 409 of a sale with another live document propagate', async () => {
      const issued = firstValueFrom(service.issue(SALES_ORDER_ID, 'attempt-1'));

      httpMock
        .expectOne(service.apiUrl)
        .flush(
          { status: 409, message: 'This sale already has a stamped document' },
          { status: 409, statusText: 'Conflict' },
        );

      await expect(issued).rejects.toMatchObject({ status: 409 });
    });

    it('lets the 503 of a deployment without a provider propagate', async () => {
      const issued = firstValueFrom(service.issue(SALES_ORDER_ID, 'attempt-1'));

      httpMock
        .expectOne(service.apiUrl)
        .flush(
          { status: 503, message: 'No stamping provider is configured' },
          { status: 503, statusText: 'Service Unavailable' },
        );

      await expect(issued).rejects.toMatchObject({ status: 503 });
    });
  });

  describe('reprintXml', () => {
    it('downloads the bytes as a blob and names the file the extended disposition states', async () => {
      const bytes = new Blob(['<cfdi:Comprobante/>'], { type: 'application/xml' });
      const reprint = firstValueFrom(service.reprintXml(DOCUMENT_ID));

      const request = httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/xml`);
      expect(request.request.method).toBe('GET');
      expect(request.request.responseType).toBe('blob');
      request.flush(bytes, {
        headers: {
          'Content-Disposition':
            'attachment; filename="=?UTF-8?Q?F-0001.xml?="; filename*=UTF-8\'\'F-0001.xml',
        },
      });

      const result = await reprint;
      expect(result.blob.size).toBe(bytes.size);
      expect(result.fileName).toBe('F-0001.xml');
    });

    it('decodes the extended form, so a folio with a space keeps its name', async () => {
      const reprint = firstValueFrom(service.reprintXml(DOCUMENT_ID));

      httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/xml`).flush(new Blob(['x']), {
        headers: {
          'Content-Disposition': "attachment; filename*=UTF-8''Factura%20A-1234.xml",
        },
      });

      await expect(reprint).resolves.toMatchObject({ fileName: 'Factura A-1234.xml' });
    });

    it('reads the plain quoted form too, because the header is the API’s and not this client’s', async () => {
      const reprint = firstValueFrom(service.reprintXml(DOCUMENT_ID));

      httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/xml`).flush(new Blob(['x']), {
        headers: { 'Content-Disposition': 'attachment; filename="CFDI-2026.xml"' },
      });

      await expect(reprint).resolves.toMatchObject({ fileName: 'CFDI-2026.xml' });
    });

    it('falls back to the document id when the header states no name', async () => {
      const reprint = firstValueFrom(service.reprintXml(DOCUMENT_ID));

      httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/xml`).flush(new Blob(['x']));

      await expect(reprint).resolves.toMatchObject({ fileName: `${DOCUMENT_ID}.xml` });
    });

    it('lets the 404 of a draft or a foreign document propagate', async () => {
      const reprint = firstValueFrom(service.reprintXml(DOCUMENT_ID));

      // A blob response converts only blob-shaped bodies, so the error is flushed as one too.
      httpMock
        .expectOne(`${service.apiUrl}/${DOCUMENT_ID}/xml`)
        .flush(new Blob([JSON.stringify({ status: 404, message: 'No artefact' })]), {
          status: 404,
          statusText: 'Not Found',
        });

      await expect(reprint).rejects.toMatchObject({ status: 404 });
    });
  });

  describe('cancel', () => {
    it('reads a 201 as granted and carries the row, the motive and the authority’s code', async () => {
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '02'));

      const request = httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toStrictEqual({ motive: '02' });
      request.flush(grantedBody(), { status: 201, statusText: 'Created' });

      const result = await cancelled;
      expect(result.kind).toBe('granted');
      expect(result as FiscalCancellationGranted).toStrictEqual({
        kind: 'granted',
        cancellationDocumentId: CANCELLATION_ID,
        cancelledDocumentId: DOCUMENT_ID,
        motive: '02',
        status: 'CANCELLED',
        statusUuid: '201',
        message: 'Comprobante cancelado correctamente',
      });
    });

    it('reads a 202 as submitted, with the authority’s code and no row', async () => {
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '02'));

      httpMock
        .expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`)
        .flush(submittedBody(), { status: 202, statusText: 'Accepted' });

      const result = await cancelled;
      expect(result.kind).toBe('submitted');
      expect(result as FiscalCancellationSubmitted).toStrictEqual({
        kind: 'submitted',
        cancelledDocumentId: DOCUMENT_ID,
        motive: '02',
        statusUuid: '211',
        message: 'Solicitud recibida; el receptor aún no la atiende',
      });
    });

    it('sends the substituting folio for motive 01, the one motive that replaces the document', async () => {
      const substituting = 'B4C5D6E7-0000-4000-8000-0000000000F6';
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '01', substituting));

      const request = httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`);
      expect(request.request.body).toStrictEqual({ motive: '01', substitutingFolio: substituting });
      request.flush(grantedBody({ motive: '01' }), { status: 201, statusText: 'Created' });

      await cancelled;
    });

    it('omits the field for a motive that replaces nothing instead of sending null', async () => {
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '03'));

      const request = httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`);
      expect(request.request.body).toStrictEqual({ motive: '03' });
      request.flush(grantedBody({ motive: '03' }), { status: 201, statusText: 'Created' });

      await cancelled;
    });

    it('drops a folio handed with a motive that does not take one, because the field is not the caller’s', async () => {
      // The authority accepts the attribute for 02–04 but nothing replaces the document there, so a screen
      // that left the field enabled must not make this client send a substituting folio the motive denies.
      const cancelled = firstValueFrom(
        service.cancel(DOCUMENT_ID, '04', 'B4C5D6E7-0000-4000-8000-0000000000F6'),
      );

      const request = httpMock.expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`);
      expect(request.request.body).toStrictEqual({ motive: '04' });
      request.flush(grantedBody({ motive: '04' }), { status: 201, statusText: 'Created' });

      await cancelled;
    });

    it('lets the 409 of an already-cancelled document propagate', async () => {
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '02'));

      httpMock
        .expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`)
        .flush(
          { status: 409, message: 'This document is already cancelled' },
          { status: 409, statusText: 'Conflict' },
        );

      await expect(cancelled).rejects.toMatchObject({ status: 409 });
    });

    it('lets the 503 of an unconfigured cancellation provider propagate', async () => {
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '02'));

      httpMock
        .expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`)
        .flush(
          { status: 503, message: 'No cancellation provider is configured' },
          { status: 503, statusText: 'Service Unavailable' },
        );

      await expect(cancelled).rejects.toMatchObject({ status: 503 });
    });

    it('refuses a success status that is neither 201 nor 202, instead of picking one of them', async () => {
      const cancelled = firstValueFrom(service.cancel(DOCUMENT_ID, '02'));

      httpMock
        .expectOne(`${service.apiUrl}/${DOCUMENT_ID}/cancellation`)
        .flush(grantedBody(), { status: 200, statusText: 'OK' });

      await expect(cancelled).rejects.toThrow(/200/);
    });
  });
});
