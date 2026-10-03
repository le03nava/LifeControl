import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { combineLatest, of } from 'rxjs';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  filter,
  map,
  startWith,
  switchMap,
} from 'rxjs/operators';
import { ErrorBanner } from '@shared/ui';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { EmployeeService } from '../../data/employee.service';
import { EmployeeStatusService } from '../../data/employee-status.service';
import { employeeStatusLabel } from '../../data/employee-status';
import {
  EmployeeControl,
  EmployeeEmailSuggestion,
  EmployeeRequest,
} from '../../models/employee.models';

/** The debounce window of the live email suggestion, matching the list's search. */
const SUGGEST_DEBOUNCE_MS = 300;

/** The seeded default status of a new employee (T5), by server name. */
const ACTIVE_STATUS_NAME = 'Active';

/** The one status that carries a termination date (T5), by server name. */
const TERMINATED_STATUS_NAME = 'Terminated';

/**
 * Copy for each machine-readable `suggest-email` reason.
 *
 * `NO_EMAIL_DOMAIN` is the blocking instruction the write path fails closed on
 * (D8); `EMPTY_LOCAL_PART` and `NO_FREE_CANDIDATE` are distinct states of the
 * suggestion, never a generic error toast. The `NO_FREE_CANDIDATE` copy says the
 * suggestion reserves nothing on purpose (T8): the 409 at save time is the
 * honest outcome the error handling surfaces.
 */
const SUGGESTION_REASON_MESSAGES: Readonly<Record<string, string>> = {
  NO_EMAIL_DOMAIN:
    'La empresa no tiene configurado un dominio de correo. Configurá el dominio de correo de la empresa antes de crear empleados: el guardado va a fallar mientras falte.',
  EMPTY_LOCAL_PART: 'Los nombres no permiten construir un correo. Escribilo manualmente.',
  NO_FREE_CANDIDATE:
    'Los correos sugeridos ya están en uso. La sugerencia no reserva nada: si otra persona guarda primero, el guardado va a fallar y hay que ajustar el correo.',
};

const UNKNOWN_SUGGESTION_REASON_MESSAGE = 'No se pudo sugerir un correo. Escribilo manualmente.';

/** Copy shown when the `EMPLOYEE_STATUS` family could not be resolved. */
const STATUS_CATALOGUE_ERROR_MESSAGE = 'No se pudo cargar el catálogo de estados.';

interface StatusOption {
  id: string;
  label: string;
}

/** Trims an optional text field; an empty or whitespace-only value becomes `null`. */
function blankToNull(value: string | null): string | null {
  const trimmed = value?.trim() ?? '';
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Create / edit screen of the employee registry.
 *
 * The company comes from the `companyId` **query param**, exactly as
 * `department-edit` does, and the two write routes are registered with the
 * employee write roles in `hr.routes.ts`.
 *
 * There is deliberately **no address control** (`G16`): `common/address` has a
 * model, a DTO and a repository but no controller, and the existing
 * `AddressFormComponent` posts a nested object rather than an id, so there is no
 * honest control to bind. The loaded employee's `addressId` is therefore kept in
 * component state and echoed back unchanged on update — `updateEmployee` sets the
 * address unconditionally, so a `null` would erase it.
 *
 * The email is suggested live from the names while the address is editable and
 * frozen (read-only, and sent as blank) once the employee has a
 * `keycloakUserId` (`T9`/`T23`). The suggestion is a read that reserves nothing
 * (`T8`).
 */
@Component({
  selector: 'app-employee-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    ErrorBanner,
  ],
  templateUrl: './employee-edit.html',
  styleUrl: './employee-edit.scss',
})
export class EmployeeEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly employeeService = inject(EmployeeService);
  private readonly statusService = inject(EmployeeStatusService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Route id: present only on the `edit/:id` child. */
  readonly employeeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.employeeId());
  /** Company from the `companyId` query param, exactly as the department form reads it. */
  readonly companyId = signal<string | null>(this.route.snapshot.queryParamMap.get('companyId'));

  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly submitError = signal<string | null>(null);

  /** True once the loaded employee carries a `keycloakUserId`; the email is then frozen. */
  readonly emailFrozen = signal(false);

  /** A free candidate offered as a hint the operator can accept explicitly. */
  readonly suggestedEmail = signal<string | null>(null);
  private readonly suggestionReason = signal<string | null>(null);

  // ─── Status catalogue ────────────────────────────────────────
  private readonly statusMap = signal<ReadonlyMap<string, string>>(new Map());
  /** The loaded employee's status, kept so a failed catalogue still shows and sends it. */
  private readonly loadedStatusId = signal<string | null>(null);
  private readonly loadedStatusName = signal<string | null>(null);
  private readonly statusCatalogueFailed = signal(false);

  /**
   * The loaded employee's `addressId`, kept out of the form on purpose (`G16`).
   * `buildRequest` echoes it back so an unrelated edit never erases the address.
   */
  private readonly loadedAddressId = signal<string | null>(null);

  private readonly statusNameById = computed<ReadonlyMap<string, string>>(
    () => new Map([...this.statusMap()].map(([name, id]) => [id, name] as const)),
  );

  /** The Spanish-labelled `<option>`s the status select renders. */
  readonly statusOptions = computed<StatusOption[]>(() => {
    const options = [...this.statusMap()].map(([name, id]) => ({
      id,
      label: employeeStatusLabel(name),
    }));
    const loadedId = this.loadedStatusId();
    if (loadedId && !options.some((option) => option.id === loadedId)) {
      const loadedName = this.loadedStatusName();
      options.unshift({
        id: loadedId,
        label: loadedName ? employeeStatusLabel(loadedName) : loadedId,
      });
    }
    return options;
  });

  readonly statusCatalogueError = computed(() =>
    this.statusCatalogueFailed() ? STATUS_CATALOGUE_ERROR_MESSAGE : null,
  );

  /**
   * True when create mode has no resolvable status catalogue. The select is then
   * empty and the only payload the client could build carries `statusId: null`,
   * which the service already rejects, so `onSubmit` fails closed instead of
   * sending it; the catalogue-failure copy is already on screen.
   */
  private readonly statusCatalogueBlocksCreate = computed(
    () => !this.isEditMode() && this.statusCatalogueFailed(),
  );

  /** The copy for the current suggestion reason, or `null` when there is none. */
  readonly suggestionMessage = computed(() => {
    const reason = this.suggestionReason();
    if (!reason) return null;
    return SUGGESTION_REASON_MESSAGES[reason] ?? UNKNOWN_SUGGESTION_REASON_MESSAGE;
  });

  /**
   * Client mirror of `EmployeeService.validateStatusAndTerminationDate`.
   *
   * It is **not** the source of truth: the service stays authoritative and its
   * 400 must still be surfaced. It exists so the operator sees the rule while
   * typing instead of only after a round trip. The error lives on the
   * termination-date control, so `onStatusChange` re-runs it when the status
   * changes (a status change does not touch that control's own value).
   */
  private readonly terminationDateForStatus: ValidatorFn = (
    control: AbstractControl,
  ): ValidationErrors | null => {
    const statusId = (control.parent?.get('statusId')?.value ?? null) as string | null;
    const statusName = this.statusNameFor(statusId);
    const hasDate = !!control.value;

    // The server compares case-insensitively (`EmployeeStatuses.isTerminated`),
    // so a differently-cased catalogue name must not disable this mirror.
    if (statusName?.toLowerCase() === TERMINATED_STATUS_NAME.toLowerCase()) {
      return hasDate ? null : { terminationDateRequired: true };
    }
    if (statusName && hasDate) {
      return { terminationDateForbidden: true };
    }
    return null;
  };

  /**
   * Client **mirror** of `EmployeeService.validateDates`, which stays the
   * authority and whose 400 is still surfaced: `birthDate` must be strictly
   * before `hireDate`, and a `terminationDate` may not be before `hireDate`. It is
   * a **group-level** validator because both rules span controls, and it exists so
   * the operator sees the rule before the round trip instead of only the server's
   * banner.
   *
   * ISO `type="date"` values compare chronologically as strings, so the string
   * comparisons below match the server's `LocalDate` comparisons exactly.
   */
  private readonly dateOrder: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
    const birthDate = (group.get('birthDate')?.value ?? '') as string;
    const hireDate = (group.get('hireDate')?.value ?? '') as string;
    const terminationDate = (group.get('terminationDate')?.value ?? '') as string;

    if (birthDate && hireDate && !(birthDate < hireDate)) {
      return { birthDateBeforeHireDate: true };
    }
    if (terminationDate && hireDate && terminationDate < hireDate) {
      return { terminationDateOnOrAfterHireDate: true };
    }
    return null;
  };

  readonly formGroup = signal<FormGroup<EmployeeControl>>(
    this.fb.group<EmployeeControl>(
      {
        employeeNumber: this.fb.control('', {
          validators: [Validators.required, Validators.maxLength(30)],
        }),
        firstName: this.fb.control('', {
          validators: [Validators.required, Validators.maxLength(100)],
        }),
        paternalLastName: this.fb.control('', {
          validators: [Validators.required, Validators.maxLength(100)],
        }),
        maternalLastName: this.fb.control<string | null>(null, {
          validators: [Validators.maxLength(100)],
        }),
        email: this.fb.control<string | null>(null, {
          validators: [Validators.email, Validators.maxLength(255)],
        }),
        phoneNumber: this.fb.control<string | null>(null, {
          validators: [Validators.maxLength(50)],
        }),
        birthDate: this.fb.control('', { validators: [Validators.required] }),
        hireDate: this.fb.control('', { validators: [Validators.required] }),
        terminationDate: this.fb.control<string | null>(null, {
          validators: [this.terminationDateForStatus],
        }),
        statusId: this.fb.control<string | null>(null),
      },
      { validators: [this.dateOrder] },
    ),
  );

  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    email: () => 'Ingresá un correo válido.',
    terminationDateRequired: () =>
      'La fecha de baja es obligatoria cuando el estado es Dado de baja.',
    terminationDateForbidden: () => 'Solo un empleado dado de baja puede tener fecha de baja.',
    serverError: (err) => err as string,
  };

  protected getErrorMessage(control: AbstractControl | null): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const firstErrorKey = Object.keys(control.errors)[0];
    const errorDetail = control.errors[firstErrorKey];
    const message = this.defaultErrorMessages[firstErrorKey];
    return message ? message(errorDetail) : 'Campo inválido.';
  }

  /**
   * The copy for the current group-level date-order error, or `null`. The error
   * lives on the group (a mirror of `EmployeeService.validateDates`), so it is
   * surfaced here rather than through `getErrorMessage`.
   */
  protected dateOrderError(): string | null {
    const errors = this.formGroup().errors;
    if (!errors) return null;
    if (errors['birthDateBeforeHireDate']) {
      return 'La fecha de nacimiento debe ser anterior a la fecha de ingreso.';
    }
    if (errors['terminationDateOnOrAfterHireDate']) {
      return 'La fecha de baja no puede ser anterior a la fecha de ingreso.';
    }
    return null;
  }

  ngOnInit(): void {
    this.loadStatusCatalogue();

    const id = this.employeeId();
    if (!id) {
      if (!this.companyId()) {
        this.loadError.set('No se indicó la empresa del empleado.');
        return;
      }
      // Create mode: the email is editable from the start, so the suggestion is
      // wired immediately. The seeded Active default arrives with the catalogue.
      this.subscribeToSuggestions();
      return;
    }

    const companyId = this.companyId();
    if (!companyId) {
      this.loadError.set('No se indicó la empresa del empleado.');
      return;
    }
    this.loadEmployee(companyId, id);
  }

  onStatusChange(statusId: string): void {
    this.formGroup().controls.statusId.setValue(statusId);
    // The cross-field rule rides the termination-date control; re-run it because
    // its own value did not change.
    this.formGroup().controls.terminationDate.updateValueAndValidity();
  }

  /** Accepts the offered hint explicitly, taking ownership of the control. */
  acceptSuggestion(): void {
    const email = this.suggestedEmail();
    if (!email) return;
    const control = this.formGroup().controls.email;
    control.setValue(email);
    control.markAsDirty();
    this.suggestedEmail.set(null);
  }

  onSubmit(): void {
    this.submitError.set(null);
    this.clearServerErrors();

    const companyId = this.companyId();
    if (!companyId) return;

    // Fail closed: create mode with a failed status catalogue can only build
    // `statusId: null`, which the client already knows the service rejects. The
    // catalogue-failure copy is already rendered, so refuse instead of a 400.
    if (this.statusCatalogueBlocksCreate()) {
      return;
    }

    if (this.formGroup().invalid) {
      this.formGroup().markAllAsTouched();
      return;
    }

    const request = this.buildRequest();
    const id = this.employeeId();
    const save$ = id
      ? this.employeeService.updateEmployee(companyId, id, request)
      : this.employeeService.addEmployee(companyId, request);

    save$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateToList(companyId),
      error: (err: HttpErrorResponse) => this.handleSubmitError(err),
    });
  }

  onCancel(): void {
    this.navigateToList(this.companyId());
  }

  /**
   * Builds the **complete** `EmployeeRequest` both routes expect, with `null`
   * for every blank optional field and the two traps handled explicitly.
   */
  private buildRequest(): EmployeeRequest {
    const value = this.formGroup().getRawValue();
    return {
      employeeNumber: value.employeeNumber.trim(),
      firstName: value.firstName.trim(),
      paternalLastName: value.paternalLastName.trim(),
      maternalLastName: blankToNull(value.maternalLastName),
      // Trap 2: a frozen address is never echoed. The service keeps the stored one
      // verbatim (T23), and echoing a stored address whose domain no longer matches
      // the company's would turn an unrelated edit into a 400.
      email: this.emailFrozen() ? null : blankToNull(value.email),
      phoneNumber: blankToNull(value.phoneNumber),
      birthDate: value.birthDate,
      hireDate: value.hireDate,
      terminationDate: blankToNull(value.terminationDate),
      // Trap 1: `updateEmployee` sets the address unconditionally, so a null would
      // erase the stored address. Carry the loaded id back unchanged.
      addressId: this.loadedAddressId(),
      statusId: value.statusId,
    };
  }

  private navigateToList(companyId: string | null): void {
    this.router.navigate(['/hr/employees'], {
      queryParams: companyId ? { companyId } : {},
    });
  }

  private loadStatusCatalogue(): void {
    this.statusService
      .loadEmployeeStatusIds()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (map) => {
          this.statusMap.set(map);
          if (!this.isEditMode()) {
            const activeId = map.get(ACTIVE_STATUS_NAME);
            if (activeId) {
              this.formGroup().controls.statusId.setValue(activeId);
            }
          }
        },
        error: () => this.statusCatalogueFailed.set(true),
      });
  }

  private loadEmployee(companyId: string, id: string): void {
    this.loading.set(true);
    this.employeeService
      .getEmployee(companyId, id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (employee) => {
          this.loadedAddressId.set(employee.addressId);
          this.loadedStatusId.set(employee.statusId);
          this.loadedStatusName.set(employee.statusName);
          this.emailFrozen.set(!!employee.keycloakUserId);
          this.formGroup().patchValue({
            employeeNumber: employee.employeeNumber,
            firstName: employee.firstName,
            paternalLastName: employee.paternalLastName,
            maternalLastName: employee.maternalLastName,
            email: employee.email,
            phoneNumber: employee.phoneNumber,
            birthDate: employee.birthDate,
            hireDate: employee.hireDate,
            terminationDate: employee.terminationDate,
            statusId: employee.statusId,
          });
          // `patchValue` walks the controls in key order; re-evaluate the cross-field
          // rule once the whole row is in place and the status is known.
          this.formGroup().controls.terminationDate.updateValueAndValidity();
          this.loading.set(false);

          // T9: once the address is frozen the pipeline is never subscribed.
          if (!employee.keycloakUserId) {
            this.subscribeToSuggestions();
          }
        },
        error: (err: HttpErrorResponse) => {
          this.loadError.set(httpErrorMessage(err));
          this.loading.set(false);
        },
      });
  }

  /**
   * Live suggestion from the two names, following `product-supplier-dialog`'s
   * RxJS idiom.
   *
   * `switchMap` is the operator that matters: a superseded request is cancelled,
   * not raced. The request fires only when both names are non-blank; `filter`
   * sits before the debounce so clearing a name cancels the pending request
   * instead of firing it with blanks.
   */
  private subscribeToSuggestions(): void {
    const companyId = this.companyId();
    if (!companyId) return;

    const { firstName, paternalLastName } = this.formGroup().controls;
    combineLatest([
      firstName.valueChanges.pipe(startWith(firstName.value)),
      paternalLastName.valueChanges.pipe(startWith(paternalLastName.value)),
    ])
      .pipe(
        map(([first, last]) => ({
          firstName: (first ?? '').trim(),
          paternalLastName: (last ?? '').trim(),
        })),
        filter((names) => names.firstName !== '' && names.paternalLastName !== ''),
        debounceTime(SUGGEST_DEBOUNCE_MS),
        distinctUntilChanged(
          (a, b) => a.firstName === b.firstName && a.paternalLastName === b.paternalLastName,
        ),
        switchMap((names) =>
          this.employeeService
            .suggestEmail(companyId, names.firstName, names.paternalLastName)
            .pipe(catchError(() => of(null as EmployeeEmailSuggestion | null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((suggestion) => this.applySuggestion(suggestion));
  }

  private applySuggestion(suggestion: EmployeeEmailSuggestion | null): void {
    this.suggestedEmail.set(null);
    this.suggestionReason.set(null);

    if (!suggestion) return;
    if (suggestion.reason) {
      this.suggestionReason.set(suggestion.reason);
      return;
    }
    if (!suggestion.email) return;

    const control = this.formGroup().controls.email;
    // Fill only a still-pristine control (a value the operator typed is never
    // overwritten). Edit mode is excluded on purpose: `patchValue` leaves the
    // hydrated control pristine, and the stored address must not be replaced.
    if (!this.isEditMode() && control.pristine) {
      control.setValue(suggestion.email);
    } else {
      this.suggestedEmail.set(suggestion.email);
    }
  }

  private statusNameFor(statusId: string | null): string | null {
    if (!statusId) return null;
    return (
      this.statusNameById().get(statusId) ??
      (statusId === this.loadedStatusId() ? this.loadedStatusName() : null)
    );
  }

  /**
   * Maps the API error envelope: a per-field error for a key that has a control
   * (every control renders a `mat-error`) lands on that control, and **every**
   * other message goes to the banner -- a key with no control at all (`addressId`)
   * or a `message`-only payload such as the 409 duplicate and the frozen-email
   * conflict. No server error is swallowed.
   */
  private handleSubmitError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    const fieldErrors = apiError?.errors ?? {};
    const unmapped: string[] = [];

    for (const [field, message] of Object.entries(fieldErrors)) {
      const control = this.formGroup().get(field);
      if (control) {
        control.setErrors({ ...(control.errors ?? {}), serverError: message });
        control.markAsTouched();
      } else {
        unmapped.push(message);
      }
    }

    if (unmapped.length > 0) {
      this.submitError.set(unmapped.join(' '));
    } else if (Object.keys(fieldErrors).length === 0) {
      this.submitError.set(apiError?.message ?? httpErrorMessage(err));
    }
  }

  private clearServerErrors(): void {
    for (const control of Object.values(this.formGroup().controls)) {
      if (control.errors && 'serverError' in control.errors) {
        const { serverError: _serverError, ...rest } = control.errors;
        control.setErrors(Object.keys(rest).length > 0 ? rest : null);
      }
    }
  }
}
