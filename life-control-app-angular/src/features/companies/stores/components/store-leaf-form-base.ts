import {
  computed,
  Directive,
  effect,
  inject,
  Injector,
  input,
  output,
  Signal,
} from '@angular/core';
import { FormGroup, NonNullableFormBuilder } from '@angular/forms';
import { Subscription } from 'rxjs';
import { createErrorMessageResolver, LEAF_FORM_ERROR_MESSAGES } from './form-errors';

/** Fields every leaf entity shares with the other two. */
export interface LeafEntity {
  readonly description: string | null;
  readonly displayOrder: number | null;
}

/**
 * Shared behaviour of the three leaf forms (store area / zone / location).
 *
 * Owns what the three forms repeated verbatim: the `save`/`cancelForm` outputs, the
 * `serverErrors` input and its control-mapping effect, the `isEditMode` flag, the
 * error-message resolver and the save guard. Each concrete form keeps only its own
 * typed `formGroup`, its entity input and its request mapping — which is why this is a
 * base class and not a config-driven component: the three templates bind
 * `formGroup.controls.<specificKey>` under `strictTemplates`, so a shared form group
 * would have to lose that typing or force the labels and keys into runtime config.
 *
 * `@Directive()` (no selector) is what lets this abstract base declare the signal
 * `input()`/`output()` the three forms inherit; Angular rejects those calls on an
 * undecorated class (NG8110).
 */
@Directive()
export abstract class StoreLeafFormBase<TRequest> {
  protected readonly fb = inject(NonNullableFormBuilder);
  private readonly injector = inject(Injector);

  readonly serverErrors = input<Record<string, string>>({});

  readonly save = output<TRequest>();
  /**
   * Named `cancelForm` (not `cancel`) because `cancel` is a native DOM event name and
   * `@angular-eslint/no-output-native` rejects it — same convention as `StoresForm`.
   */
  readonly cancelForm = output<void>();

  /** The entity being edited; `null` means create mode. Owned (and named) by each form. */
  protected abstract readonly entity: Signal<LeafEntity | null>;

  /** Typed group of the four leaf controls, owned by each form. */
  protected abstract readonly formGroup: FormGroup;

  /** Component name used in the server-error console warning. */
  protected abstract readonly formLabel: string;

  /** Maps the raw control values to this form's request payload. */
  protected abstract buildRequest(): TRequest;

  readonly isEditMode = computed(() => !!this.entity());

  protected readonly getErrorMessage = createErrorMessageResolver(LEAF_FORM_ERROR_MESSAGES);

  /**
   * Maps `serverErrors` onto the matching controls and clears each `serverError` on the
   * next value change. Called from the concrete form's constructor so the effect is still
   * created during construction, exactly like the three forms did before.
   */
  protected bindServerErrors(): void {
    effect(
      (onCleanup) => {
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
                control.setErrors(remainingKeys.length > 0 ? otherErrors : null, {
                  emitEvent: true,
                });
              }
            });
            subscriptions.push(sub);
          } else {
            console.warn(`[${this.formLabel}] No control found for server error key: "${key}"`);
          }
        });

        onCleanup(() => {
          subscriptions.forEach((sub) => sub.unsubscribe());
        });
      },
      { injector: this.injector },
    );
  }

  // ─── Methods ────────────────────────────────────────────────
  onSave(): void {
    this.formGroup.markAllAsTouched();

    if (this.formGroup.invalid) return;

    this.save.emit(this.buildRequest());
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
