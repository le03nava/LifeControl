import { ChangeDetectionStrategy, Component, effect, input, output } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import {
  SchedulingActivityControl,
  SchedulingActivityFormValue,
} from '../../models/scheduling-activity.models';

/**
 * Presentational reactive form for a scheduling activity.
 *
 * The owning page builds and owns the `FormGroup` (so it can re-seed it after a
 * 412, mark it pristine before navigating, and answer the unsaved-changes guard);
 * this component only renders the controls, maps server errors onto them and
 * emits the raw value.
 *
 * `enabled` is deliberately **not** a control: enabling and disabling are owned by
 * `PATCH /{id}/enable` and `DELETE /{id}`. Sending the flag from here would create
 * a second writer for one piece of state and would silently re-enable a disabled
 * activity that someone merely edited.
 */
@Component({
  selector: 'app-scheduling-activity-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './scheduling-activity-form.html',
  styleUrl: './scheduling-activity-form.scss',
})
export class SchedulingActivityForm {
  readonly formGroup = input.required<FormGroup<SchedulingActivityControl>>();
  readonly serverErrors = input<Record<string, string>>({});

  /** Whether the form presents its edit copy. */
  readonly isEditMode = input(false);

  /** True while a create/update request is in flight; disables the submit control. */
  readonly saving = input(false);

  /** False for a read-only user: the submit control is not rendered at all. */
  readonly canWrite = input(true);

  /**
   * Why the save control is unavailable even when the form itself is valid.
   *
   * The owning page sets this while it could not read the authoritative state:
   * without the loaded `version` the page would send no optimistic-lock
   * precondition, so a save would overwrite another session's write instead of
   * conflicting with it. The control stays visible so the operator can read the
   * reason and retry the read.
   */
  readonly saveBlockedReason = input<string | null>(null);

  readonly save = output<SchedulingActivityFormValue>();
  readonly cancelForm = output<void>();

  /** Spanish copy for the validation errors the controls can raise. */
  readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    min: (err) => `El valor mínimo es ${(err as { min?: number }).min}.`,
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    serverError: (err) => err as string,
  };

  protected getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: unknown) => string>,
  ): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    const allMessages = { ...this.defaultErrorMessages, ...customMessages };
    const resolver = allMessages[primerErrorKey];
    if (resolver) {
      return resolver(errorDetalle);
    }

    return 'Campo inválido.';
  }

  constructor() {
    // Maps a backend `errors` map onto the matching controls.
    //
    // Nothing here clears a `serverError`: `setErrors` only writes the control's
    // error bag, and the next `setValue` — what a keystroke reaches through the
    // value accessor — runs `updateValueAndValidity`, which recomputes `errors`
    // from the registered validators and drops the hand-set `serverError` before
    // `valueChanges` emits. A `valueChanges` callback would therefore run after
    // the flag is already gone and could never clear anything, so there is none.
    effect(() => {
      const serverErrors = this.serverErrors();
      const fg = this.formGroup();

      if (!fg || Object.keys(serverErrors).length === 0) return;

      Object.entries(serverErrors).forEach(([key, message]) => {
        const control = fg.get(key);
        if (control) {
          const currentErrors = control.errors || {};
          control.setErrors({ ...currentErrors, serverError: message }, { emitEvent: false });
        } else {
          console.warn(`[SchedulingActivityForm] No control found for server error key: "${key}"`);
        }
      });
    });
  }

  onSave(): void {
    this.formGroup().markAllAsTouched();

    // The constraint is enforced here, at the emission boundary, and not only on
    // the submit control: an implicit form submission or a direct call to this
    // handler must be blocked too, so a later re-templating cannot reopen the gap.
    if (this.formGroup().valid && !this.saving() && !this.saveBlockedReason()) {
      this.save.emit(this.formGroup().getRawValue());
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
