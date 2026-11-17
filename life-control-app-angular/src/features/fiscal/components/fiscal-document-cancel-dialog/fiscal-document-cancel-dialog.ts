import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import type { Observable } from 'rxjs';
import {
  CANCELLATION_MOTIVE_OPTIONS,
  requiresSubstitutingFolio,
} from '@shared/fiscal/cancellation-motive';
import {
  cancellationOutcomeCopy,
  cancellationRefusalCopy,
  type CancellationOutcome,
  type CancellationRefusal,
} from '../../data/fiscal-cancellation-copy';
import type { FiscalCancellationResult } from '../../data/fiscal-document.models';
import { FiscalDocumentService } from '../../data/fiscal-document.service';

/** What the dialog needs from the panel: the document it is cancelling, and nothing the panel cannot state. */
export interface FiscalDocumentCancelDialogData {
  readonly documentId: string;
}

interface CancellationControl {
  motive: FormControl<string>;
  substitutingFolio: FormControl<string>;
}

/**
 * Cancels one stamped document, with the authority's motive and the folio that replaces it (`S4`, `S5`, `S6`).
 *
 * **The motive is the authority's vocabulary, not a text field.** The four codes come from
 * `shared/fiscal/cancellation-motive.ts`, the client's copy of `FiscalCancellationMotive`, kept in step with
 * the Java enum by its own parity spec — a cancellation that states a code the API does not know is a `400`
 * on a form that looked fine.
 *
 * **The substituting folio is asked for by the one motive that replaces the document.** `01` replaces it, so
 * `requiresSubstitutingFolio('01')` is true and the field is on screen and required; for the other three the
 * field is not rendered **and the request does not carry it at all** — not as an empty string and not as
 * `null`, because the field states a document that does not exist and an absent field states the truth (the
 * service drops it too; the dialog is what refuses to ask for it).
 *
 * **The two answers are rendered as what they are, and the dialog does not close on either.** A `201` means
 * the authority cancelled the document and the API wrote the row; a `202` means the request was **submitted
 * and not accepted yet**, the document is still valid, and the screen must never contradict that (`S5`). The
 * answer is kept and the dialog closes with it, so the panel can refresh a granted cancellation and keep a
 * submitted one on screen instead of forgetting it.
 */
@Component({
  selector: 'app-fiscal-document-cancel-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
  ],
  templateUrl: './fiscal-document-cancel-dialog.html',
  styleUrl: './fiscal-document-cancel-dialog.scss',
})
export class FiscalDocumentCancelDialog {
  private readonly dialogRef =
    inject<MatDialogRef<FiscalDocumentCancelDialog, FiscalCancellationResult | null>>(MatDialogRef);
  private readonly fiscalDocumentService = inject(FiscalDocumentService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<FiscalDocumentCancelDialogData>(MAT_DIALOG_DATA);

  /** The four codes, in the authority's own order and with its own wording. */
  readonly motiveOptions = CANCELLATION_MOTIVE_OPTIONS;

  readonly form = new FormGroup<CancellationControl>({
    motive: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    substitutingFolio: new FormControl('', { nonNullable: true }),
  });

  /** The chosen motive as a signal, because whether the folio is asked for is a fact of the template. */
  private readonly chosenMotive = toSignal(this.form.controls.motive.valueChanges, {
    initialValue: '',
  });

  /** `S4`: the folio field exists only for the motive that replaces the document. */
  readonly requiresFolio = computed(() => requiresSubstitutingFolio(this.chosenMotive()));

  readonly saving = signal(false);

  /** The answer, once the API stated one of its two kinds. */
  readonly outcome = signal<CancellationOutcome | null>(null);

  /** The refusal, once the API refused — its own copy, never a generic banner (`S6`). */
  readonly refusal = signal<CancellationRefusal | null>(null);

  /** The answer as the service stated it, which is what the dialog closes with. */
  private answer: FiscalCancellationResult | null = null;

  constructor() {
    this.form.controls.motive.valueChanges.pipe(takeUntilDestroyed()).subscribe((motive) => {
      const folio = this.form.controls.substitutingFolio;
      // The validator travels with the motive: a folio that is required under `01` and not asked for under
      // the rest cannot be required by a static rule, or the form would be permanently invalid.
      folio.setValidators(
        requiresSubstitutingFolio(motive) ? [Validators.required, Validators.pattern(/\S/)] : [],
      );
      folio.updateValueAndValidity({ emitEvent: false });
    });
  }

  /**
   * Asks the authority to cancel, and keeps its answer.
   *
   * Esc and a backdrop click are refused while the request is in flight: Material closes the ref itself on
   * both and answers `undefined`, which the panel reads as "no answer" while the request it did not wait for
   * is still running. The error branch restores the exit.
   */
  submit(): void {
    if (this.saving() || this.outcome() !== null) {
      return;
    }

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    this.saving.set(true);
    this.refusal.set(null);
    this.dialogRef.disableClose = true;

    this.request(value.motive, value.substitutingFolio)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.dialogRef.disableClose = false;
          this.saving.set(false);
          this.answer = result;
          this.outcome.set(cancellationOutcomeCopy(result));
        },
        error: (error: unknown) => {
          this.dialogRef.disableClose = false;
          this.saving.set(false);
          this.refusal.set(cancellationRefusalCopy(error));
        },
      });
  }

  /** Closes with the answer, so the panel refreshes what the API wrote and keeps what it did not. */
  close(): void {
    this.dialogRef.close(this.answer);
  }

  /** Closes with nothing: the operator changed their mind before anything was asked. */
  cancel(): void {
    this.dialogRef.close(null);
  }

  /**
   * The cancellation, with the folio **only** where the motive replaces the document.
   *
   * The two calls are distinct on purpose: under any motive but `01` the service is called with two arguments,
   * so there is no third value to drop, to send empty or to send as `null`. The service applies the same rule
   * for every caller that is not this dialog; here it is what makes the field's absence and the request's
   * absence the same fact.
   */
  private request(motive: string, substitutingFolio: string): Observable<FiscalCancellationResult> {
    return requiresSubstitutingFolio(motive)
      ? this.fiscalDocumentService.cancel(this.data.documentId, motive, substitutingFolio.trim())
      : this.fiscalDocumentService.cancel(this.data.documentId, motive);
  }
}
