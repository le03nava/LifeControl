import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
  CreateStoreLocationRequest,
  StoreLocation,
  StoreLocationControl,
  UpdateStoreLocationRequest,
} from '../../models/store-location.models';

/**
 * Self-contained reactive form for creating and editing a store location.
 * Emits a cleaned request payload; the page owns the API call.
 */
@Component({
  selector: 'app-store-location-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './store-location-form.html',
  styleUrl: './store-location-form.scss',
})
export class StoreLocationForm {
  private readonly fb = inject(NonNullableFormBuilder);

  // ─── Inputs ─────────────────────────────────────────────────
  readonly storeLocation = input<StoreLocation | null>(null);
  readonly serverErrors = input<Record<string, string>>({});

  // ─── Outputs ────────────────────────────────────────────────
  readonly save = output<CreateStoreLocationRequest | UpdateStoreLocationRequest>();
  /**
   * Named `cancelForm` (not `cancel`) because `cancel` is a native DOM event name and
   * `@angular-eslint/no-output-native` rejects it — same convention as `StoresForm`.
   */
  readonly cancelForm = output<void>();

  // ─── Self-contained FormGroup ───────────────────────────────
  readonly formGroup = this.fb.group<StoreLocationControl>({
    locationCode: this.fb.control('', [Validators.required, Validators.maxLength(10)]),
    locationName: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
    description: this.fb.control<string | null>(null, [Validators.maxLength(255)]),
    displayOrder: this.fb.control<number | null>(null, [Validators.min(0)]),
  });

  readonly isEditMode = computed(() => !!this.storeLocation());

  // ─── Error messages (Spanish copy) ──────────────────────────
  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    min: (err) => `El valor mínimo permitido es ${(err as { min?: number }).min}.`,
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
    if (allMessages[primerErrorKey]) {
      return allMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  }

  constructor() {
    // --- Edit mode: patch the form whenever a store location arrives ---
    effect(() => {
      const storeLocation = this.storeLocation();
      if (!storeLocation) return;

      this.formGroup.patchValue({
        locationCode: storeLocation.locationCode,
        locationName: storeLocation.locationName,
        description: storeLocation.description,
        displayOrder: storeLocation.displayOrder,
      });
    });

    // --- Server errors: map to controls, clear on value change ---
    effect((onCleanup) => {
      const errors = this.serverErrors();
      const fg = this.formGroup;
      if (!fg || Object.keys(errors).length === 0) return;

      const subscriptions: Subscription[] = [];

      Object.entries(errors).forEach(([key, message]) => {
        const control = fg.get(key);
        if (control) {
          const currentErrors = control.errors || {};
          control.setErrors({ ...currentErrors, serverError: message }, { emitEvent: false });

          const sub = control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(remainingKeys.length > 0 ? otherErrors : null, { emitEvent: true });
            }
          });
          subscriptions.push(sub);
        } else {
          console.warn(`[StoreLocationForm] No control found for server error key: "${key}"`);
        }
      });

      onCleanup(() => {
        subscriptions.forEach((sub) => sub.unsubscribe());
      });
    });
  }

  // ─── Methods ────────────────────────────────────────────────
  onSave(): void {
    this.formGroup.markAllAsTouched();

    if (this.formGroup.invalid) return;

    const raw = this.formGroup.getRawValue();
    const locationCode = raw.locationCode.trim();
    const locationName = raw.locationName.trim();
    const description = raw.description?.trim();
    const displayOrder = raw.displayOrder;

    this.save.emit({
      locationCode,
      locationName,
      ...(description ? { description } : {}),
      ...(displayOrder !== null && displayOrder !== undefined ? { displayOrder } : {}),
    });
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
