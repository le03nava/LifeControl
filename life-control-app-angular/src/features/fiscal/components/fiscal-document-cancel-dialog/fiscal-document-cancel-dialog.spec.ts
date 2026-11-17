import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { CANCELLATION_MOTIVE_OPTIONS } from '@shared/fiscal/cancellation-motive';
import type { ApiError } from '@shared/models';
import type { FiscalCancellationResult } from '../../data/fiscal-document.models';
import { FiscalDocumentService } from '../../data/fiscal-document.service';
import { FiscalDocumentCancelDialog } from './fiscal-document-cancel-dialog';

const DOCUMENT_ID = 'document-1';
const CANCELLATION_ID = 'cancellation-1';
/** The fiscal UUID of the document that replaces the cancelled one: what motive `01` has to state. */
const SUBSTITUTING_UUID = '11111111-2222-3333-4444-555555555555';

/** The authority granted the cancellation and the API wrote the row: `201` (`S5`). */
function granted(): FiscalCancellationResult {
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
function submitted(): FiscalCancellationResult {
  return {
    kind: 'submitted',
    cancelledDocumentId: DOCUMENT_ID,
    motive: '02',
    statusUuid: '211',
    message: 'La solicitud de cancelación está en proceso.',
  };
}

/** The API's own refusal: an `ApiError` body, whose `message` carries which of a status's causes it was. */
function apiFailure(status: number, message: string): HttpErrorResponse {
  const body: ApiError = {
    status,
    message,
    path: `/api/fiscal-documents/${DOCUMENT_ID}/cancellation`,
    timestamp: '2026-11-18T09:00:00-06:00',
    correlationId: 'correlation-1',
  };
  return new HttpErrorResponse({ status, error: body });
}

describe('FiscalDocumentCancelDialog', () => {
  let fixture: ComponentFixture<FiscalDocumentCancelDialog>;
  let component: FiscalDocumentCancelDialog;
  let cancel: ReturnType<typeof vi.fn>;
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean };

  async function build(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [FiscalDocumentCancelDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { documentId: DOCUMENT_ID } },
        { provide: FiscalDocumentService, useValue: { cancel } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(FiscalDocumentCancelDialog);
    component = fixture.componentInstance;
    await settle();
  }

  beforeEach(() => {
    cancel = vi.fn(() => of(granted()));
    dialogRef = { close: vi.fn(), disableClose: false };
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function byTestId(id: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      `[data-testid="${id}"]`,
    );
  }

  /** The folio field, which `S4` asks for under motive `01` and under nothing else. */
  const folioField = (): HTMLElement | null => byTestId('fiscal-cancel-folio');

  /** Chooses a motive the way the select does: by setting the value the option carries. */
  async function chooseMotive(code: string): Promise<void> {
    component.form.controls.motive.setValue(code);
    component.form.controls.motive.markAsTouched();
    await settle();
  }

  /** Types the substituting folio into the field the operator sees, not into the form behind it. */
  async function typeFolio(value: string): Promise<void> {
    const input = folioField()?.querySelector('input') as HTMLInputElement | null;
    if (!input) {
      throw new Error('The substituting-folio field is not on screen.');
    }
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    await settle();
  }

  async function press(label: string): Promise<void> {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.trim() === label);
    if (!button) {
      throw new Error(`No button reads "${label}".`);
    }
    button.click();
    await settle();
  }

  describe('the motive (S4)', () => {
    it('offers the four codes of the shared vocabulary, each with its own wording', async () => {
      await build();

      expect(CANCELLATION_MOTIVE_OPTIONS).toHaveLength(4);

      // A `mat-select` renders the wording of the option that matches the control's value, so seeing the
      // label is seeing the option: an option the select does not know would render nothing.
      for (const option of CANCELLATION_MOTIVE_OPTIONS) {
        await chooseMotive(option.code);
        expect(text()).toContain(option.label);
      }
    });

    it('asks for the substituting folio under motive 01 and under no other', async () => {
      await build();

      expect(folioField()).toBeNull();

      await chooseMotive('01');
      expect(folioField()).not.toBeNull();

      for (const code of ['02', '03', '04']) {
        await chooseMotive(code);
        expect(folioField()).toBeNull();
      }
    });

    it('sends the substituting folio it collected under motive 01', async () => {
      await build();
      await chooseMotive('01');
      await typeFolio(SUBSTITUTING_UUID);

      await press('Cancelar documento');

      expect(cancel).toHaveBeenCalledTimes(1);
      expect(cancel.mock.calls[0]).toEqual([DOCUMENT_ID, '01', SUBSTITUTING_UUID]);
    });

    it('omits the folio altogether for a motive that replaces nothing', async () => {
      await build();
      // The field is filled under `01` and the motive is changed afterwards, so the omission is a decision
      // about the request and not an empty field nobody typed in.
      await chooseMotive('01');
      await typeFolio(SUBSTITUTING_UUID);
      await chooseMotive('02');

      await press('Cancelar documento');

      // Two arguments: the field is not sent as an empty string and not sent as `undefined`.
      expect(cancel.mock.calls[0]).toEqual([DOCUMENT_ID, '02']);
    });

    it('does not ask the API for motive 01 without the folio it requires', async () => {
      await build();
      await chooseMotive('01');

      await press('Cancelar documento');

      expect(cancel).not.toHaveBeenCalled();
      expect(text()).toContain('El motivo 01 exige el UUID fiscal');
    });
  });

  describe('the two answers (S5)', () => {
    it('renders a granted cancellation as the document being cancelled', async () => {
      cancel.mockReturnValue(of(granted()));
      await build();
      await chooseMotive('02');

      await press('Cancelar documento');

      expect(byTestId('fiscal-cancel-outcome-granted')).not.toBeNull();
      expect(text()).toContain('El documento se canceló ante el SAT.');
      expect(text()).toContain('201');
      expect(text()).not.toContain('todavía no está aceptada');
    });

    it('renders a submitted cancellation as submitted and not accepted, with the authority code', async () => {
      cancel.mockReturnValue(of(submitted()));
      await build();
      await chooseMotive('02');

      await press('Cancelar documento');

      expect(byTestId('fiscal-cancel-outcome-submitted')).not.toBeNull();
      expect(text()).toContain('La cancelación se envió y todavía no está aceptada');
      expect(text()).toContain('sigue vigente');
      expect(text()).toContain('211');
      // The defect `S5` exists to prevent: a submission rendered as a cancellation.
      expect(text()).not.toContain('El documento se canceló ante el SAT.');
    });

    it('closes with the answer it received, so the panel refreshes on a granted cancellation', async () => {
      const answer = granted();
      cancel.mockReturnValue(of(answer));
      await build();
      await chooseMotive('02');
      await press('Cancelar documento');

      await press('Cerrar');

      expect(dialogRef.close).toHaveBeenCalledWith(answer);
    });

    it('closes with nothing while no answer exists', async () => {
      await build();

      await press('Volver');

      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });
  });

  describe('the refusals (S4, S6)', () => {
    it('renders an already-cancelled document or a refused request as its own state, with the API message', async () => {
      cancel.mockReturnValue(
        throwError(() =>
          apiFailure(
            409,
            'The authority rejected the cancellation of fiscal document document-1 with 1 issue(s), ' +
              'and nothing was written: 301 El comprobante no se puede cancelar.',
          ),
        ),
      );
      await build();
      await chooseMotive('03');

      await press('Cancelar documento');

      expect(byTestId('fiscal-cancel-refusal-already-cancelled-or-refused')).not.toBeNull();
      expect(text()).toContain('El documento ya se había cancelado');
      expect(text()).toContain('The authority rejected the cancellation');
      // The other two causes are their own states, not this one's copy.
      expect(byTestId('fiscal-cancel-refusal-unknown-document')).toBeNull();
      expect(byTestId('fiscal-cancel-refusal-cannot-cancel-here')).toBeNull();
    });

    it('renders a 404 as a document that is not stamped in this company', async () => {
      cancel.mockReturnValue(
        throwError(() =>
          apiFailure(404, 'Fiscal document document-1 is not a stamped document of this company.'),
        ),
      );
      await build();
      await chooseMotive('02');

      await press('Cancelar documento');

      expect(byTestId('fiscal-cancel-refusal-unknown-document')).not.toBeNull();
      expect(text()).toContain('puede ser un borrador o de otra compañía');
      expect(text()).toContain('is not a stamped document of this company');
      expect(byTestId('fiscal-cancel-refusal-already-cancelled-or-refused')).toBeNull();
      expect(byTestId('fiscal-cancel-refusal-cannot-cancel-here')).toBeNull();
    });

    it('renders a 503 as a deployment that cannot cancel, not as a document that cannot be', async () => {
      cancel.mockReturnValue(
        throwError(() =>
          apiFailure(
            503,
            'Fiscal document document-1 cannot be cancelled: no cancellation provider is configured.',
          ),
        ),
      );
      await build();
      await chooseMotive('02');

      await press('Cancelar documento');

      expect(byTestId('fiscal-cancel-refusal-cannot-cancel-here')).not.toBeNull();
      expect(text()).toContain('Esta instalación no puede cancelar');
      expect(text()).toContain('no hay un proveedor de cancelación configurado');
      expect(text()).toContain('no cancellation provider is configured');
      expect(byTestId('fiscal-cancel-refusal-unknown-document')).toBeNull();
      expect(byTestId('fiscal-cancel-refusal-already-cancelled-or-refused')).toBeNull();
    });
  });
});
