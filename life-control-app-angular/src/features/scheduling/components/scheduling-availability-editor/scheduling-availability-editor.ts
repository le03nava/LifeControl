import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import {
  MAX_AVAILABILITY_WINDOWS,
  SchedulingAvailabilityFormControl,
  SchedulingAvailabilityRowControl,
} from '../../models/scheduling-activity.models';

/** One weekday option of the selector: ISO-8601 `1..7`, `MONDAY = 1` (D47). */
interface WeekdayOption {
  value: number;
  label: string;
}

/**
 * Presentational editor for an activity's weekly availability windows.
 *
 * The owning page builds and owns the `FormGroup` (so it can seed it from the
 * server read, adopt the ordered response after a save, mark it pristine and
 * answer the unsaved-changes guard); this component renders the rows, maps their
 * validation errors and emits the row and save intents. It performs no HTTP.
 *
 * The hours are native `<input type="time">` and the dates native
 * `<input type="date">` (D40, no date primitive and no date library in the app);
 * the wire string conversion lives in `scheduling-availability-wire.ts`.
 */
@Component({
  selector: 'app-scheduling-availability-editor',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './scheduling-availability-editor.html',
  styleUrl: './scheduling-availability-editor.scss',
})
export class SchedulingAvailabilityEditor {
  readonly formGroup = input.required<FormGroup<SchedulingAvailabilityFormControl>>();

  /** False for a read-only user: no add, remove or save control is rendered. */
  readonly canWrite = input(true);

  /** True while the save request is in flight; disables the submit control. */
  readonly saving = input(false);

  /** The server's cap on the window set; the add action is blocked at it. */
  readonly maxWindows = input(MAX_AVAILABILITY_WINDOWS);

  readonly save = output<void>();
  readonly cancelForm = output<void>();
  readonly addWindow = output<void>();
  readonly removeWindow = output<number>();

  protected readonly weekdays: WeekdayOption[] = [
    { value: 1, label: 'Lunes' },
    { value: 2, label: 'Martes' },
    { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' },
    { value: 5, label: 'Viernes' },
    { value: 6, label: 'Sábado' },
    { value: 7, label: 'Domingo' },
  ];

  readonly windows = computed(() => this.formGroup().controls.windows);

  /** The rows typed for the template; `FormArray.controls` is the live array. */
  protected readonly rowGroups = computed(
    () => this.windows().controls as FormGroup<SchedulingAvailabilityRowControl>[],
  );

  /** Spanish copy for the errors the controls and the rows can raise. */
  protected readonly errorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    min: (err) => `El valor mínimo es ${(err as { min?: number }).min}.`,
    max: (err) => `El valor máximo es ${(err as { max?: number }).max}.`,
    serverError: (err) => err as string,
  };

  protected getErrorMessage(control: AbstractControl | null): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const errorKey = Object.keys(control.errors)[0];
    const resolver = this.errorMessages[errorKey];
    return resolver ? resolver(control.errors[errorKey]) : 'Campo inválido.';
  }

  /**
   * The cross-field errors the row's own group validator raises. They belong to no
   * single control, so they render once under the row.
   */
  protected rowError(row: FormGroup<SchedulingAvailabilityRowControl>): string | null {
    if (!row.touched || !row.errors) {
      return null;
    }
    if (row.hasError('endTimeNotAfterStart')) {
      return 'La hora de fin debe ser posterior a la hora de inicio.';
    }
    if (row.hasError('validToBeforeValidFrom')) {
      return 'La fecha de fin no puede ser anterior a la fecha de inicio.';
    }
    return null;
  }

  /**
   * The set-level overlap error, rendered once above the rows.
   *
   * It is keyed on the inconsistency itself, never on the array's `touched` flag.
   * The submit control is disabled by that same inconsistency, so the reason must
   * be readable whenever it exists: every path that makes the set overlap (adding a
   * default row, editing a time or a weekday, removing a row) re-runs the array
   * validator and sets `overlappedWindows`, while those paths touch the array at
   * most incidentally. Field-level messages keep their own `touched` semantics, so
   * a pristine form still does not shout before the operator interacts with it.
   */
  protected overlapError(): string | null {
    return this.windows().hasError('overlappedWindows')
      ? 'Dos ventanas del mismo día no pueden superponerse.'
      : null;
  }

  protected atCapacity(): boolean {
    return this.windows().length >= this.maxWindows();
  }

  onSave(): void {
    const form = this.formGroup();
    form.markAllAsTouched();

    // The constraint is enforced at the emission boundary, not only on the submit
    // control, so a direct call or a later re-templating cannot reopen the gap.
    if (form.valid && !this.saving()) {
      this.save.emit();
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }

  onAddWindow(): void {
    if (this.atCapacity()) {
      return;
    }
    this.addWindow.emit();
  }

  onRemoveWindow(index: number): void {
    this.removeWindow.emit(index);
  }
}
