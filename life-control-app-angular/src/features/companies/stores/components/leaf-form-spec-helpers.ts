import { OutputEmitterRef, Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormGroup } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
// Imported explicitly (instead of relying on vitest globals) because this file is not a
// `*.spec.ts`, so the app build's TS program type-checks it without the test globals.
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Public surface of a leaf form (area / zone / location) that the shared behavioral
 * suite drives. It mirrors the public API of `StoreLeafFormBase` subclasses only —
 * protected members and private signals stay out of the specs on purpose.
 */
export interface LeafFormHarness<TRequest> {
  readonly formGroup: FormGroup;
  readonly save: OutputEmitterRef<TRequest>;
  readonly cancelForm: OutputEmitterRef<void>;
  isEditMode(): boolean;
  onSave(): void;
  onCancel(): void;
}

/** Values the edit-mode effect must copy from the entity input onto the form controls. */
export interface LeafFormPatchedValues {
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  readonly displayOrder: number | null;
}

/**
 * Per-component descriptor that lets one behavioral suite run for all three leaf forms.
 * Everything the three former near-copies did identically is inferred from these fields;
 * only the keys, the entity shape and the expected request payload change per component.
 */
export interface LeafFormCaseDef<TRequest> {
  /** Component name, used in failure messages. */
  readonly formName: string;
  /** Standalone component class under test. */
  readonly componentClass: Type<LeafFormHarness<TRequest>>;
  /** Name of the entity input signal (`area`, `storeZone`, `storeLocation`). */
  readonly entityInput: string;
  /** Key of the required, `maxLength(10)` code control (`areaCode`, `zoneCode`, ...). */
  readonly codeKey: string;
  /** Key of the required, `maxLength(100)` name control (`areaName`, `zoneName`, ...). */
  readonly nameKey: string;
  /** Entity with all optional fields present, used by the edit-mode patching cases. */
  readonly entity: unknown;
  /** Same entity with `description` and `displayOrder` cleared to `null`. */
  readonly entityWithoutOptionals: unknown;
  /** Values the edit-mode effect must copy onto the controls for `entity`. */
  readonly expectedPatched: LeafFormPatchedValues;
  /** Payload expected when the description is blank and `displayOrder` is absent. */
  readonly expectedTrimmedRequest: Record<string, unknown>;
  /** Payload expected when the description is present and `displayOrder` is `0`. */
  readonly expectedRequest: Record<string, unknown>;
}

export interface LeafFormFixture<TRequest> {
  readonly fixture: ComponentFixture<LeafFormHarness<TRequest>>;
  readonly component: LeafFormHarness<TRequest>;
}

/**
 * Builds a standalone fixture for a leaf form. Shared by the behavioral suite and the
 * thin per-component config specs so both use exactly the same bootstrapping.
 */
export async function createLeafFormFixture<TRequest>(
  componentClass: Type<LeafFormHarness<TRequest>>,
): Promise<LeafFormFixture<TRequest>> {
  await TestBed.configureTestingModule({
    imports: [componentClass, NoopAnimationsModule],
  }).compileComponents();

  const fixture = TestBed.createComponent(componentClass);
  const component = fixture.componentInstance;
  fixture.detectChanges();

  return { fixture, component };
}

/**
 * The union of the behavior the three leaf-form specs used to assert three times:
 * create/edit mode, entity patching, the four validators and their Spanish messages,
 * the `onSave` guard and payload cleaning, `onCancel`, and server-error mapping.
 *
 * Component-specific copy (titles, submit labels, control keys) is intentionally left
 * to the thin config spec that calls this suite.
 */
export function runLeafFormBehavioralSuite<TRequest>(caseDef: LeafFormCaseDef<TRequest>): void {
  const { componentClass, codeKey, nameKey, entityInput } = caseDef;

  describe('shared behavior', () => {
    let fixture: ComponentFixture<LeafFormHarness<TRequest>>;
    let component: LeafFormHarness<TRequest>;

    beforeEach(async () => {
      ({ fixture, component } = await createLeafFormFixture(componentClass));
    });

    function control(key: string) {
      const found = component.formGroup.get(key);
      if (!found) throw new Error(`[${caseDef.formName}] form control "${key}" is missing`);
      return found;
    }

    function setEntity(entity: unknown): void {
      fixture.componentRef.setInput(entityInput, entity);
      fixture.detectChanges();
    }

    function matErrorText(): string {
      const nodes: Element[] = Array.from(fixture.nativeElement.querySelectorAll('mat-error'));
      return nodes.map((node) => node.textContent ?? '').join(' ');
    }

    function captureSave(): () => TRequest | undefined {
      let emitted: TRequest | undefined;
      component.save.subscribe((value) => {
        emitted = value;
      });
      return () => emitted;
    }

    /** Fills the two required controls so the group is valid and `saving` is the only blocker. */
    function fillValidForm(): void {
      component.formGroup.patchValue({ [codeKey]: 'VALID', [nameKey]: 'Valid' });
    }

    function submitButton(): HTMLButtonElement {
      return fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    }

    // ─── Mode ────────────────────────────────────────────────
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should not be in edit mode by default', () => {
      expect(component.isEditMode()).toBe(false);
    });

    it('should enter edit mode when the entity input is set', () => {
      setEntity(caseDef.entity);

      expect(component.isEditMode()).toBe(true);
    });

    it(`should patch ${codeKey} from the entity input`, () => {
      setEntity(caseDef.entity);

      expect(control(codeKey).value).toBe(caseDef.expectedPatched.code);
    });

    it(`should patch ${nameKey} from the entity input`, () => {
      setEntity(caseDef.entity);

      expect(control(nameKey).value).toBe(caseDef.expectedPatched.name);
    });

    it('should patch description from the entity input', () => {
      setEntity(caseDef.entity);

      expect(control('description').value).toBe(caseDef.expectedPatched.description);
    });

    it('should patch displayOrder from the entity input', () => {
      setEntity(caseDef.entity);

      expect(control('displayOrder').value).toBe(caseDef.expectedPatched.displayOrder);
    });

    it('should patch description as null when the entity has no description', () => {
      setEntity(caseDef.entityWithoutOptionals);

      expect(control('description').value).toBeNull();
    });

    it('should patch displayOrder as null when the entity has no displayOrder', () => {
      setEntity(caseDef.entityWithoutOptionals);

      expect(control('displayOrder').value).toBeNull();
    });

    // ─── Validation ──────────────────────────────────────────
    it(`should require ${codeKey}`, () => {
      expect(control(codeKey).errors?.['required']).toBeTruthy();
    });

    it(`should require ${nameKey}`, () => {
      expect(control(nameKey).errors?.['required']).toBeTruthy();
    });

    it(`should enforce maxLength 10 on ${codeKey}`, () => {
      const code = control(codeKey);
      code.setValue('A'.repeat(11));
      expect(code.errors?.['maxlength']).toBeTruthy();

      code.setValue('A'.repeat(10));
      expect(code.errors).toBeNull();
    });

    it(`should enforce maxLength 100 on ${nameKey}`, () => {
      const name = control(nameKey);
      name.setValue('A'.repeat(101));
      expect(name.errors?.['maxlength']).toBeTruthy();

      name.setValue('A'.repeat(100));
      expect(name.errors).toBeNull();
    });

    it('should enforce maxLength 255 on description', () => {
      const description = control('description');
      description.setValue('A'.repeat(256));
      expect(description.errors?.['maxlength']).toBeTruthy();

      description.setValue('A'.repeat(255));
      expect(description.errors).toBeNull();
    });

    it('should enforce min 0 on displayOrder', () => {
      const displayOrder = control('displayOrder');
      displayOrder.setValue(-1);
      expect(displayOrder.errors?.['min']).toBeTruthy();

      displayOrder.setValue(0);
      expect(displayOrder.errors).toBeNull();
    });

    it('should reject a fractional displayOrder', () => {
      const displayOrder = control('displayOrder');
      displayOrder.setValue(1.5);
      expect(displayOrder.errors?.['integer']).toBeTruthy();

      displayOrder.setValue(2);
      expect(displayOrder.errors).toBeNull();
    });

    it('should render the Spanish required message', () => {
      const name = control(nameKey);
      name.markAsTouched();
      name.setErrors({ required: true });
      fixture.detectChanges();

      expect(matErrorText()).toContain('obligatorio');
    });

    it('should render the Spanish maxlength message', () => {
      const code = control(codeKey);
      code.setValue('A'.repeat(11));
      code.markAsTouched();
      fixture.detectChanges();

      expect(matErrorText()).toContain('No puede superar los 10');
    });

    it('should render the Spanish min message', () => {
      const displayOrder = control('displayOrder');
      displayOrder.setValue(-5);
      displayOrder.markAsTouched();
      fixture.detectChanges();

      expect(matErrorText()).toContain('mínimo');
    });

    it('should render the Spanish integer message', () => {
      const displayOrder = control('displayOrder');
      displayOrder.setValue(1.5);
      displayOrder.markAsTouched();
      fixture.detectChanges();

      expect(matErrorText()).toContain('entero');
    });

    // ─── Save ────────────────────────────────────────────────
    it('should not emit save when the form is invalid', () => {
      let emitted = false;
      component.save.subscribe(() => {
        emitted = true;
      });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should mark the code and name controls as touched when saving an invalid form', () => {
      component.onSave();

      expect(control(codeKey).touched).toBe(true);
      expect(control(nameKey).touched).toBe(true);
    });

    it('should emit trimmed code and name values', () => {
      component.formGroup.patchValue({
        [codeKey]: `  ${caseDef.expectedTrimmedRequest[codeKey]}  `,
        [nameKey]: `  ${caseDef.expectedTrimmedRequest[nameKey]}  `,
        description: '   ',
        displayOrder: null,
      });
      const emitted = captureSave();

      component.onSave();

      expect(emitted()).toEqual(caseDef.expectedTrimmedRequest);
    });

    it('should omit blank description and absent displayOrder from the request', () => {
      component.formGroup.patchValue({
        [codeKey]: caseDef.expectedTrimmedRequest[codeKey],
        [nameKey]: caseDef.expectedTrimmedRequest[nameKey],
        description: '   ',
        displayOrder: null,
      });
      const emitted = captureSave();

      component.onSave();

      const payload = emitted() as Record<string, unknown>;
      expect('description' in payload).toBe(false);
      expect('displayOrder' in payload).toBe(false);
    });

    it('should include description and displayOrder when present', () => {
      component.formGroup.patchValue({
        [codeKey]: caseDef.expectedRequest[codeKey],
        [nameKey]: caseDef.expectedRequest[nameKey],
        description: `  ${caseDef.expectedRequest['description']}  `,
        displayOrder: 0,
      });
      const emitted = captureSave();

      component.onSave();

      // 0 is a valid display order and must not be dropped as "empty".
      expect(emitted()).toEqual(caseDef.expectedRequest);
    });

    // ─── Submit gating ──────────────────────────────────────
    it('should disable the submit control while the form is invalid', () => {
      expect(submitButton().disabled).toBe(true);
    });

    it('should disable the submit control while a save is in flight', () => {
      fillValidForm();
      fixture.componentRef.setInput('saving', true);
      fixture.detectChanges();

      expect(submitButton().disabled).toBe(true);
    });

    it('should not emit save while a save is already in flight', () => {
      fillValidForm();
      fixture.componentRef.setInput('saving', true);
      fixture.detectChanges();
      let emitted = false;
      component.save.subscribe(() => {
        emitted = true;
      });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should not render the submit control for a read-only user', () => {
      fixture.componentRef.setInput('canWrite', false);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
    });

    it('should keep the cancel control for a read-only user', () => {
      fixture.componentRef.setInput('canWrite', false);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('button[type="button"]')).toBeTruthy();
    });

    // ─── Cancel ──────────────────────────────────────────────
    it('should emit cancelForm', () => {
      let emitted = false;
      component.cancelForm.subscribe(() => {
        emitted = true;
      });

      component.onCancel();

      expect(emitted).toBe(true);
    });

    // ─── Server errors ───────────────────────────────────────
    it('should map server errors onto the matching control', () => {
      control(codeKey).setValue('VALID');
      control(nameKey).setValue('Valid');

      fixture.componentRef.setInput('serverErrors', { [codeKey]: 'Código duplicado' });
      fixture.detectChanges();

      expect(control(codeKey).errors?.['serverError']).toBe('Código duplicado');
    });

    it('should clear the server error when the control value changes', () => {
      control(codeKey).setValue('VALID');
      control(nameKey).setValue('Valid');

      fixture.componentRef.setInput('serverErrors', { [codeKey]: 'Código duplicado' });
      fixture.detectChanges();

      control(codeKey).setValue('VALID-2');

      expect(control(codeKey).errors?.['serverError']).toBeUndefined();
    });

    it('should keep other validation errors when clearing the server error', () => {
      fixture.componentRef.setInput('serverErrors', { [nameKey]: 'Nombre repetido' });
      fixture.detectChanges();

      // Clear the value: `required` stays, `serverError` goes away.
      control(nameKey).setValue('');

      expect(control(nameKey).errors?.['required']).toBeTruthy();
      expect(control(nameKey).errors?.['serverError']).toBeUndefined();
    });

    it('should render the server error message through mat-error', () => {
      control(nameKey).setValue('Valid');
      control(nameKey).markAsTouched();

      fixture.componentRef.setInput('serverErrors', { [nameKey]: 'Nombre repetido' });
      fixture.detectChanges();

      expect(matErrorText()).toContain('Nombre repetido');
    });

    it('should ignore unknown server error keys', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

      fixture.componentRef.setInput('serverErrors', { unknownField: 'boom' });
      fixture.detectChanges();

      expect(warnSpy).toHaveBeenCalled();
      warnSpy.mockRestore();
    });
  });
}
