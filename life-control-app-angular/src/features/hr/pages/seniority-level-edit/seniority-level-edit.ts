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
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ErrorBanner } from '@shared/ui';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { SeniorityLevelService } from '../../data/seniority-level.service';
import {
  SeniorityLevelControl,
  SeniorityLevelRequest,
} from '../../models/seniority-level.models';

/**
 * Create / edit screen of the **global** seniority-level catalog.
 *
 * It carries **no company selector**, unlike every other HR form: seniority
 * levels are global reference data (record D1) and the path has no `companyId`
 * to seed one from. The screen is reached from Puestos (D5), and its own list
 * page is where both outcomes return to.
 *
 * The three fields are the three the API requires. `rank` is the one that is not
 * decoration: the salary-band editor reads the global catalog **ordered by
 * rank**, and the unique constraint covers code, name and rank together, which
 * is what the 409 the banner reports actually means.
 *
 * There is **no version control and no 412 branch** (T2).
 */
@Component({
  selector: 'app-seniority-level-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    ErrorBanner,
  ],
  templateUrl: './seniority-level-edit.html',
  styleUrl: './seniority-level-edit.scss',
})
export class SeniorityLevelEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly seniorityLevelService = inject(SeniorityLevelService);
  private readonly destroyRef = inject(DestroyRef);

  /** Route id: present only on the `edit/:id` child. */
  readonly levelId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.levelId());

  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly submitError = signal<string | null>(null);
  readonly serverErrors = signal<Record<string, string>>({});

  readonly formGroup = new FormGroup<SeniorityLevelControl>({
    levelCode: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(10)],
    }),
    levelName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(50)],
    }),
    // Required and nullable at once: an empty input must not reach the API as
    // `NaN`, and the API accepts no rank below 1.
    rank: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(1)],
    }),
    enabled: new FormControl(true, { nonNullable: true }),
  });

  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    min: (err) => `El valor mínimo es ${(err as { min?: number }).min}.`,
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

  ngOnInit(): void {
    const id = this.levelId();
    if (!id) return;

    this.loading.set(true);
    this.seniorityLevelService
      .getSeniorityLevel(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (level) => {
          this.formGroup.patchValue({
            levelCode: level.levelCode,
            levelName: level.levelName,
            rank: level.rank,
            enabled: level.enabled,
          });
          this.loading.set(false);
        },
        error: (err: HttpErrorResponse) => {
          this.loadError.set(httpErrorMessage(err));
          this.loading.set(false);
        },
      });
  }

  onSubmit(): void {
    this.submitError.set(null);
    this.clearServerErrors();

    if (this.formGroup.invalid) {
      this.formGroup.markAllAsTouched();
      return;
    }

    const request = this.buildRequest();
    if (!request) {
      this.formGroup.markAllAsTouched();
      return;
    }

    const id = this.levelId();
    const save$ = id
      ? this.seniorityLevelService.updateSeniorityLevel(id, request)
      : this.seniorityLevelService.addSeniorityLevel(request);

    save$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateToList(),
      error: (err: HttpErrorResponse) => this.handleSubmitError(err),
    });
  }

  onCancel(): void {
    this.navigateToList();
  }

  /**
   * `null` rather than a defaulted rank: the guard above is what keeps a value
   * the operator never chose from reaching the API, and it keeps the compiler
   * honest about the nullable control at the same time.
   */
  private buildRequest(): SeniorityLevelRequest | null {
    const value = this.formGroup.getRawValue();
    if (value.rank === null) {
      return null;
    }
    return {
      levelCode: value.levelCode.trim(),
      levelName: value.levelName.trim(),
      rank: value.rank,
      enabled: value.enabled,
    };
  }

  private navigateToList(): void {
    this.router.navigate(['/hr/seniority-levels']);
  }

  /**
   * Maps the API error envelope: a per-field `errors` map lands on the controls
   * (rendered through `getErrorMessage`'s `serverError` entry), anything else
   * becomes the banner. The 409 duplicate message is a `message`, not a field
   * map, so it takes the banner path.
   */
  private handleSubmitError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      for (const [field, message] of Object.entries(apiError.errors)) {
        const control = this.formGroup.get(field);
        if (control) {
          control.setErrors({ ...(control.errors ?? {}), serverError: message });
          control.markAsTouched();
        }
      }
      return;
    }
    this.submitError.set(apiError?.message ?? httpErrorMessage(err));
  }

  private clearServerErrors(): void {
    this.serverErrors.set({});
    for (const control of Object.values(this.formGroup.controls)) {
      if (control.errors && 'serverError' in control.errors) {
        const { serverError: _serverError, ...rest } = control.errors;
        control.setErrors(Object.keys(rest).length > 0 ? rest : null);
      }
    }
  }
}
