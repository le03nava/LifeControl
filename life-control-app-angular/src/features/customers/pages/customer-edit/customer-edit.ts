import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, NonNullableFormBuilder, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { Country } from '@features/companies/countries/models/country.models';
import { CountryService } from '@features/countries/data/country.service';
import { ApiError } from '@shared/models';
import { ErrorBanner } from '@shared/ui';
import { CustomerForm } from '../../components/customer-form/customer-form';
import { CustomerService } from '../../data/customer.service';
import {
  Customer,
  CustomerControl,
  CustomerFormValue,
  CustomerRequest,
  FiscalProfileControl,
} from '../../models/customer.models';
import { fiscalProfileCompleteValidator } from '../../validation/fiscal-profile-complete';

/**
 * The shape of an RFC, which is all this screen checks (`S6`: the server already validates it).
 *
 * It is `RFCValidator.RFC_PATTERN` — three or four letters (Ñ and `&` are valid RFC characters), six digits,
 * three alphanumerics — and it is a copy for one reason only: a typo caught in the field is cheaper than a 400
 * round trip. The authority on the shape is the API, not this constant.
 */
const RFC_PATTERN = /^[A-ZÑ&]{3,4}\d{6}[A-Za-z0-9]{3}$/;

/** The five digits `CustomerRequest.fiscalZipCode` declares with `@Pattern("\\d{5}")`. Blank stays valid. */
const FISCAL_ZIP_CODE_PATTERN = /^\d{5}$/;

/**
 * One page for both modes — create and edit — of the customer.
 *
 * Modelled on `companies/pages/company-edit/company-edit.ts`: an `id` signal read from the route, `ngOnInit`
 * branching on it, the page owning the form group, and a `buildCustomerRequest` written key by key. The form
 * itself is presentational (`components/customer-form`), so the layout and the fiscal block's rule render
 * without the page knowing which control is on screen.
 *
 * **Saving bifurcates.** A create navigates to the edit page of the id the API answered, so the operator stays
 * on the row just created and can complete its fiscal profile in the same flow; an update returns to the list.
 *
 * **What this page cannot do, and does not pretend to (`L5`).** The API's update writes a fiscal field only
 * when the request carries it, and a blank is normalized to `null` — which means *leave what is stored*. So
 * emptying one fiscal field of a row that already declares a profile is not expressible through this screen:
 * the body says `null` and the row keeps its value. The alternative would be inventing a delete endpoint, which
 * `S6` excludes. The all-or-nothing rule keeps the result consistent either way: a profile is never left
 * half-stored.
 */
@Component({
  selector: 'app-customer-edit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ErrorBanner, CustomerForm],
  templateUrl: './customer-edit.html',
  styleUrl: './customer-edit.scss',
})
export class CustomerEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly customerService = inject(CustomerService);
  private readonly countryService = inject(CountryService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  /** The row being edited, or `null` on the create page. */
  readonly id = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  readonly form = this.createForm();

  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  /**
   * The fiscal country catalog, from the existing `GET /api/countries`.
   *
   * A select and not a free-text field: `fiscalCountryId` is a `UUID`, and a typo typed by hand would reach the
   * database as a foreign-key failure instead of a named refusal (`L3(b)`).
   */
  readonly countries = toSignal(
    this.countryService.getCountries().pipe(catchError(() => of([] as Country[]))),
    { initialValue: [] },
  );

  ngOnInit(): void {
    const id = this.id();
    if (id) {
      this.loadCustomer(id);
    }
  }

  private loadCustomer(id: string): void {
    this.customerService
      .getCustomerById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (customer) => this.form.patchValue(this.toFormValue(customer)),
        error: (err) => {
          console.error('[CustomerEdit] Error loading customer:', err);
          this.generalError.set('No se pudo cargar el cliente. Intente de nuevo más tarde.');
        },
      });
  }

  /**
   * The loaded row as the form holds it.
   *
   * The seven fiscal components are hydrated one by one, not as a group patch: the model declares them
   * `null`-able and the form holds strings, so `null` has to become the empty string or the select would show
   * "null" and the body would carry it.
   */
  private toFormValue(customer: Customer): Partial<CustomerFormValue> {
    return {
      id: customer.id,
      name: customer.name,
      email: customer.email ?? '',
      phone: customer.phone ?? '',
      rfc: customer.rfc ?? '',
      salesChannel: customer.salesChannel,
      enabled: customer.enabled,
      fiscal: {
        legalName: customer.legalName ?? '',
        regimenFiscal: customer.regimenFiscal ?? '',
        fiscalStreet: customer.fiscalStreet ?? '',
        fiscalZipCode: customer.fiscalZipCode ?? '',
        fiscalState: customer.fiscalState ?? '',
        fiscalCountryId: customer.fiscalCountryId ?? '',
        defaultUsoCfdi: customer.defaultUsoCfdi ?? '',
      },
    };
  }

  private createFiscalGroup(): FormGroup<FiscalProfileControl> {
    return new FormGroup<FiscalProfileControl>(
      {
        // Nothing is required here, on purpose: the profile is optional, and the all-or-nothing rule is what
        // refuses the partial shape. A `required` on any of them would demand the profile from a cash customer.
        legalName: new FormControl('', { nonNullable: true }),
        regimenFiscal: new FormControl('', { nonNullable: true }),
        fiscalStreet: new FormControl('', { nonNullable: true }),
        fiscalZipCode: new FormControl('', {
          nonNullable: true,
          validators: [Validators.pattern(FISCAL_ZIP_CODE_PATTERN)],
        }),
        fiscalState: new FormControl('', { nonNullable: true }),
        fiscalCountryId: new FormControl('', { nonNullable: true }),
        defaultUsoCfdi: new FormControl('', { nonNullable: true }),
      },
      { validators: [fiscalProfileCompleteValidator] },
    );
  }

  private createForm(): FormGroup<CustomerControl> {
    return this.fb.group<CustomerControl>({
      id: this.fb.control(''),
      name: this.fb.control('', Validators.required),
      email: this.fb.control('', Validators.email),
      phone: this.fb.control(''),
      rfc: this.fb.control('', Validators.pattern(RFC_PATTERN)),
      // Required and free text (`L3(a)`): the API demands it, no endpoint serves a vocabulary, and the only
      // value the tree seeds is `TODOS`. A select would be a catalog invented here, and pre-selecting `TODOS`
      // would write a meaning nobody chose.
      salesChannel: this.fb.control('', Validators.required),
      enabled: this.fb.control(true),
      fiscal: this.createFiscalGroup(),
    });
  }

  onSaveCustomer(value: CustomerFormValue): void {
    const request = this.buildCustomerRequest(value);

    if (value.id === '') {
      this.customerService
        .createCustomer(request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (created) => {
            this.router.navigate(['/customers/edit', created.id]);
          },
          error: (err: HttpErrorResponse) => this.handleServerError(err),
        });
      return;
    }

    this.customerService
      .updateCustomer(value.id, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.router.navigate(['/customers']);
        },
        error: (err: HttpErrorResponse) => this.handleServerError(err),
      });
  }

  /**
   * Builds the wire body explicitly against `CustomerRequest`, key by key.
   *
   * Written out on purpose: the response model carries `id`, `createdAt` and `updatedAt`, none of which the API
   * request DTO declares, and the API refuses an undeclared key **before** it validates any field. A spread of
   * the form value would put `id` back on the wire, so an extra key here is a compile error instead of a 400.
   *
   * A blank becomes `null` and not `''`: the API normalizes both to `null` on every nullable component, and
   * `null` is the honest way to say "nothing declared" — an empty string is not a legal name, a régime or a
   * postal code. `fiscalCountryId` has to be `null` too: it is a `UUID` on the API, and `''` would fail
   * deserialization rather than mean "no country".
   */
  private buildCustomerRequest(value: CustomerFormValue): CustomerRequest {
    return {
      name: value.name,
      email: this.blankToNull(value.email),
      phone: this.blankToNull(value.phone),
      rfc: this.blankToNull(value.rfc),
      salesChannel: value.salesChannel,
      enabled: value.enabled,
      legalName: this.blankToNull(value.fiscal.legalName),
      regimenFiscal: this.blankToNull(value.fiscal.regimenFiscal),
      fiscalStreet: this.blankToNull(value.fiscal.fiscalStreet),
      fiscalZipCode: this.blankToNull(value.fiscal.fiscalZipCode),
      fiscalState: this.blankToNull(value.fiscal.fiscalState),
      fiscalCountryId: this.blankToNull(value.fiscal.fiscalCountryId),
      defaultUsoCfdi: this.blankToNull(value.fiscal.defaultUsoCfdi),
    };
  }

  private blankToNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }

  cancelForm(): void {
    this.router.navigate(['/customers']);
  }
}
