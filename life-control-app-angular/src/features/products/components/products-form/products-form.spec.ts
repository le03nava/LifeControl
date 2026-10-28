import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { MatSelect } from '@angular/material/select';
import { ProductsForm } from './products-form';
import {
  ProductAttributeDefinition,
  ProductAttributeProfile,
  ProductControl,
} from '../../models/product.models';
import { measurementUnitName, measurementValueName } from '../../models/product-attribute-form';
import { ProductCategory } from '../../models/product-category.models';

describe('ProductsForm', () => {
  let component: ProductsForm;
  let fixture: ComponentFixture<ProductsForm>;

  function createFormGroup(): FormGroup<ProductControl> {
    return new FormGroup<ProductControl>({
      id: new FormControl('', { nonNullable: true }),
      sku: new FormControl('', {
        nonNullable: true,
        validators: Validators.required,
      }),
      name: new FormControl('', {
        nonNullable: true,
        validators: Validators.required,
      }),
      shortName: new FormControl<string | null>(null),
      satCode: new FormControl<string | null>(null),
      productType: new FormControl<string | null>(null),
      attributes: new FormControl<string | null>(null),
      enabled: new FormControl<boolean>(true, { nonNullable: true }),
      categoryId: new FormControl('', { nonNullable: true }),
      categoryRootId: new FormControl('', { nonNullable: true }),
      subcategory1Id: new FormControl('', { nonNullable: true }),
      subcategory2Id: new FormControl('', { nonNullable: true }),
    });
  }

  function createCategory(overrides: Partial<ProductCategory> = {}): ProductCategory {
    return {
      id: 'root-a',
      companyId: 'company-1',
      parentCategoryId: null,
      categoryName: 'Alimentos',
      description: null,
      satCode: null,
      displayOrder: 0,
      depth: 1,
      enabled: true,
      createdAt: '',
      updatedAt: '',
      ...overrides,
    };
  }

  /**
   * Two roots, one of them two levels deep, and one node without a `satCode` in each shape.
   *
   * `root-a` carries a key and has a grandchild with its own; `sub1-a` has none, which is the S5
   * shape; `root-b` (no root key) is what a second root looks like; `sub1-disabled` is disabled, so a
   * saved path over it has to render while the node is not selectable (S8).
   */
  const categoryTree: ProductCategory[] = [
    createCategory({ id: 'root-a', categoryName: 'Alimentos', satCode: '11111111' }),
    createCategory({
      id: 'sub1-a',
      parentCategoryId: 'root-a',
      categoryName: 'Bebidas',
      depth: 2,
    }),
    createCategory({
      id: 'sub2-a',
      parentCategoryId: 'sub1-a',
      categoryName: 'Jugos',
      satCode: '22222222',
      depth: 3,
    }),
    createCategory({ id: 'root-b', categoryName: 'Servicios' }),
    createCategory({
      id: 'sub1-b',
      parentCategoryId: 'root-b',
      categoryName: 'Consultoría',
      depth: 2,
      enabled: false,
    }),
  ];

  /** The ids a level currently offers, in render order. */
  function optionIds(options: { id: string }[]): string[] {
    return options.map((option) => option.id);
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductsForm],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductsForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', createFormGroup());
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display the form title for new product', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Nuevo Producto');
  });

  it('should display the form title for edit mode', () => {
    const editFg = createFormGroup();
    editFg.patchValue({ id: '123' });
    fixture.componentRef.setInput('formGroup', editFg);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Editar Producto');
  });

  it('should let an explicit editMode input override the id derivation', () => {
    // The create stepper (D26) writes the created id into the control after construction,
    // and the derivation cannot see that write. The input is the host's way to say it.
    const editFg = createFormGroup();
    editFg.patchValue({ id: '123' });
    fixture.componentRef.setInput('formGroup', editFg);
    fixture.componentRef.setInput('editMode', false);
    fixture.detectChanges();

    expect(component.isEditMode()).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nuevo Producto');
  });

  it('should present the edit copy for an explicit editMode true with no id yet', () => {
    fixture.componentRef.setInput('editMode', true);
    fixture.detectChanges();

    expect(component.isEditMode()).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Editar Producto');
  });

  it('should fall back to the id derivation when editMode is null', () => {
    expect(component.editMode()).toBeNull();
    expect(component.isEditMode()).toBe(false);

    const editFg = createFormGroup();
    editFg.patchValue({ id: '123' });
    fixture.componentRef.setInput('formGroup', editFg);
    fixture.detectChanges();

    expect(component.isEditMode()).toBe(true);
  });

  it('should emit saveProduct with parsed product data when form is valid', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    const fg = component.formGroup();
    fg.patchValue({
      sku: 'PROD-001',
      name: 'Producto Test',
      attributes: '{"color": "red"}',
      enabled: true,
    });
    fixture.detectChanges();

    component.onSave();

    expect(spy).toHaveBeenCalledTimes(1);
    const emitted = spy.mock.calls[0][0];
    expect(emitted.sku).toBe('PROD-001');
    expect(emitted.name).toBe('Producto Test');
    expect(emitted.attributes).toEqual({ color: 'red' });
    expect(emitted.enabled).toBe(true);
  });

  it('should not emit saveProduct when form is invalid', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    component.onSave();

    expect(spy).not.toHaveBeenCalled();
  });

  it('should emit cancelForm when cancel is clicked', () => {
    const spy = vi.fn();
    component.cancelForm.subscribe(spy);

    component.onCancel();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('should show mat-error for required fields when touched', () => {
    ['sku', 'name'].forEach((key) => {
      const control = component.formGroup().get(key);
      control?.markAsTouched();
      control?.setErrors({ required: true });
    });
    fixture.detectChanges();

    const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
    expect(matErrors.length).toBe(2);
  });

  it('should apply server errors to matching controls', () => {
    fixture.componentRef.setInput('serverErrors', {
      sku: 'SKU ya registrado',
    });
    fixture.detectChanges();

    const skuControl = component.formGroup().controls.sku;
    expect(skuControl.errors?.['serverError']).toBe('SKU ya registrado');
  });

  it('should warn on unmatched server error keys', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    fixture.componentRef.setInput('serverErrors', {
      nonexistent: 'No existe',
    });
    fixture.detectChanges();

    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('nonexistent'));

    warnSpy.mockRestore();
  });

  it('should clear serverError on valueChanges while preserving other validators', () => {
    const skuControl = component.formGroup().controls.sku;

    fixture.componentRef.setInput('serverErrors', {
      sku: 'SKU ya registrado',
    });
    fixture.detectChanges();

    expect(skuControl.errors?.['serverError']).toBe('SKU ya registrado');

    skuControl.setValue('NEW-SKU');
    fixture.detectChanges();

    expect(skuControl.errors?.['serverError']).toBeUndefined();
  });

  it('should parse attributes JSON string on save', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    const fg = component.formGroup();
    fg.patchValue({
      sku: 'PROD-002',
      name: 'Producto JSON',
      attributes: '{"size": "L", "color": "blue"}',
    });
    fixture.detectChanges();

    component.onSave();

    const emitted = spy.mock.calls[0][0];
    expect(emitted.attributes).toEqual({ size: 'L', color: 'blue' });
  });

  it('should set attributes as undefined when JSON is empty', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    const fg = component.formGroup();
    fg.patchValue({
      sku: 'PROD-003',
      name: 'No Attributes',
      attributes: '',
    });
    fixture.detectChanges();

    component.onSave();

    const emitted = spy.mock.calls[0][0];
    expect(emitted.attributes).toBeUndefined();
  });

  describe('attribute field-set (S4, S5, S7)', () => {
    function definition(
      overrides: Partial<ProductAttributeDefinition> = {},
    ): ProductAttributeDefinition {
      return {
        code: 'marca',
        label: 'Marca',
        level: 'PRODUCT',
        kind: 'TEXT',
        unitLabel: null,
        required: false,
        values: [],
        ...overrides,
      };
    }

    function profile(
      productAttributes: ProductAttributeDefinition[],
      variantAttributes: ProductAttributeDefinition[] = [],
    ): ProductAttributeProfile {
      return {
        profileKey: 'ferreteria',
        label: 'Ferretería',
        resolvedRootName: 'Ferretería',
        productAttributes,
        variantAttributes,
      };
    }

    /** The host's half of the contract: it reads the profile and hands it down (T4). */
    function withProfile(value: ProductAttributeProfile | null): void {
      fixture.componentRef.setInput('attributeProfile', value);
      fixture.detectChanges();
    }

    function labels(): string[] {
      return Array.from<Element>(fixture.nativeElement.querySelectorAll('mat-label')).map((label) =>
        (label.textContent ?? '').trim(),
      );
    }

    function fieldSet(): HTMLElement | null {
      return fixture.nativeElement.querySelector('.attribute-fields') as HTMLElement | null;
    }

    /** The operator's edits, through the rendered field-set (the same way the house specs drive Material). */
    function fill(values: Record<string, unknown>): void {
      component.attributeForm().patchValue(values);
      fixture.detectChanges();
    }

    /** Saves a product that satisfies the product-level form and returns the emitted `attributes`. */
    function savePayload(): unknown {
      const spy = vi.fn();
      component.saveProduct.subscribe(spy);
      component.formGroup().patchValue({ sku: 'PROD-ATTR', name: 'Producto con atributos' });
      component.onSave();
      return spy.mock.calls[0]?.[0]?.attributes;
    }

    it('should render the field-set of the selected category (S4)', () => {
      withProfile(
        profile([
          definition({ code: 'marca', label: 'Marca' }),
          definition({
            code: 'sistema_rosca',
            label: 'Sistema de rosca',
            kind: 'ENUM',
            values: [{ code: 'npt', label: 'NPT' }],
          }),
        ]),
      );

      expect(labels()).toEqual(expect.arrayContaining(['Marca', 'Sistema de rosca']));
      expect(fieldSet()?.querySelectorAll('mat-form-field').length).toBe(2);
    });

    it('should replace the field-set and drop the previous codes when the category changes (S4, L4)', () => {
      withProfile(profile([definition({ code: 'marca', label: 'Marca' })]));
      fill({ marca: 'Patito' });

      const garantia = definition({
        code: 'garantia_meses',
        label: 'Garantía en meses',
        kind: 'MEASUREMENT',
        unitLabel: 'meses',
      });
      withProfile(profile([garantia]));

      expect(labels()).not.toContain('Marca');
      expect(labels()).toContain('Garantía en meses');

      // The write rule is authoritative: a code the new profile does not declare must not travel, or
      // the API refuses the whole product.
      fill({ [measurementValueName(garantia)]: 12 });
      expect(savePayload()).toEqual({ garantia_meses: { value: 12, unit: 'meses' } });
    });

    it('should render a TEXT definition as an input and write the string (S5)', () => {
      withProfile(profile([definition({ code: 'marca', label: 'Marca' })]));

      expect(fieldSet()?.querySelector('input[type="text"]')).not.toBeNull();

      fill({ marca: 'Patito' });
      expect(savePayload()).toEqual({ marca: 'Patito' });
    });

    it('should render an ENUM definition as a single select and write the value code (S5)', () => {
      withProfile(
        profile([
          definition({
            code: 'sistema_rosca',
            label: 'Sistema de rosca',
            kind: 'ENUM',
            values: [
              { code: 'npt', label: 'NPT' },
              { code: 'metrica', label: 'Milimétrica' },
            ],
          }),
        ]),
      );

      const select = fieldSet()?.querySelector('mat-select');
      expect(select).not.toBeNull();
      expect(select?.hasAttribute('multiple')).toBe(false);

      fill({ sistema_rosca: 'npt' });
      // The code travels, never the label the operator reads (S5).
      expect(savePayload()).toEqual({ sistema_rosca: 'npt' });
    });

    it('should render a MULTI_ENUM definition as a multiple select and write the array (S5)', () => {
      withProfile(
        profile([
          definition({
            code: 'conectividad',
            label: 'Conectividad',
            kind: 'MULTI_ENUM',
            values: [
              { code: 'wifi', label: 'Wi-Fi' },
              { code: 'bluetooth', label: 'Bluetooth' },
            ],
          }),
        ]),
      );

      expect(fieldSet()?.querySelector('mat-select')?.hasAttribute('multiple')).toBe(true);

      fill({ conectividad: ['wifi', 'bluetooth'] });
      expect(savePayload()).toEqual({ conectividad: ['wifi', 'bluetooth'] });
    });

    it('should render a MEASUREMENT as a number plus a unit and store both (S5)', () => {
      const medida = definition({
        code: 'medida',
        label: 'Medida',
        kind: 'MEASUREMENT',
        unitLabel: 'pulgada',
      });
      withProfile(profile([medida]));

      expect(fieldSet()?.querySelector('input[type="number"]')).not.toBeNull();
      // The suggested unit is a hint and a default, never a constraint.
      expect(component.attributeForm().get(measurementUnitName(medida))?.value).toBe('pulgada');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('pulgada');

      fill({
        [measurementValueName(medida)]: 3,
        [measurementUnitName(medida)]: 'metros',
      });
      expect(savePayload()).toEqual({ medida: { value: 3, unit: 'metros' } });
    });

    it('should render a BOOLEAN definition as a slide toggle and write the flag (S5)', () => {
      withProfile(
        profile([definition({ code: 'garantia', label: 'Tiene garantía', kind: 'BOOLEAN' })]),
      );

      expect(fieldSet()?.querySelector('mat-slide-toggle')).not.toBeNull();

      fill({ garantia: true });
      expect(savePayload()).toEqual({ garantia: true });
    });

    it('should leave an optional attribute the operator never filled out of the payload', () => {
      withProfile(
        profile([
          definition({ code: 'marca', label: 'Marca' }),
          definition({ code: 'modelo', label: 'Modelo' }),
        ]),
      );

      fill({ marca: 'Patito' });
      // Absent, never `""` and never `null`: the API refuses a null inside the map.
      expect(savePayload()).toEqual({ marca: 'Patito' });
    });

    it('should block the submit and state the error when a required attribute is missing', () => {
      withProfile(profile([definition({ code: 'marca', label: 'Marca', required: true })]));
      const spy = vi.fn();
      component.saveProduct.subscribe(spy);
      component.formGroup().patchValue({ sku: 'PROD-ATTR', name: 'Producto con atributos' });

      component.onSave();
      fixture.detectChanges();

      expect(spy).not.toHaveBeenCalled();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Este campo es obligatorio.',
      );
    });

    it('should never render a VARIANT-level definition on the product form (S7)', () => {
      withProfile(
        profile(
          // A profile that (wrongly) lists a variant attribute among the product ones: the level is
          // the contract, so the form filters on it rather than trusting the partition slot.
          [
            definition({ code: 'marca', label: 'Marca' }),
            definition({ code: 'color', label: 'Color', level: 'VARIANT', kind: 'ENUM' }),
          ],
          [definition({ code: 'acabado', label: 'Acabado', level: 'VARIANT', kind: 'ENUM' })],
        ),
      );

      expect(labels()).toContain('Marca');
      expect(labels()).not.toContain('Color');
      expect(labels()).not.toContain('Acabado');
      expect(fieldSet()?.querySelectorAll('mat-form-field').length).toBe(1);
    });

    it('should say so, without inputs, and not author the carrier when the branch declares no profile (204)', () => {
      const fg = component.formGroup();
      fg.controls.attributes.setValue('{"marca":"Patito"}');

      withProfile(null);

      expect(fieldSet()).toBeNull();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('no declara atributos');
      // A branch that declares no profile is not the form's to author: writing `{}` here would clear
      // the attributes the product already stores, and no screen ever showed them to the operator.
      expect(fg.controls.attributes.value).toBe('{"marca":"Patito"}');
    });

    it('should project the declared set on save, so an undeclared stored key cannot block it (F1)', () => {
      // The API validates the STORED set, so a key this branch does not declare would refuse every save
      // of the product — name edits included — with no field on screen able to remove it. The save
      // therefore sends exactly what the branch declares, and the notice names what goes.
      const fg = component.formGroup();
      fg.controls.sku.setValue('SKU-1');
      fg.controls.name.setValue('Producto');
      fg.controls.attributes.setValue('{"marca":"Patito","modelo":"X"}');
      withProfile(profile([definition({ code: 'marca', label: 'Marca' })]));

      expect((fixture.nativeElement as HTMLElement).textContent).toContain('modelo');

      const spy = vi.fn();
      component.saveProduct.subscribe(spy);
      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0].attributes).toEqual({ marca: 'Patito' });
    });

    it('should save {} for a branch with no profile and name what it discards', () => {
      const fg = component.formGroup();
      fg.controls.sku.setValue('SKU-2');
      fg.controls.name.setValue('Producto');
      fg.controls.attributes.setValue('{"material":"pvc"}');
      withProfile(null);

      expect((fixture.nativeElement as HTMLElement).textContent).toContain('material');

      const spy = vi.fn();
      component.saveProduct.subscribe(spy);
      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0].attributes).toEqual({});
    });

    it('should leave the carrier alone while the profile is unknown', () => {
      // `undefined` is "the host does not know yet"; clearing the carrier here is what would drop the
      // attributes a product already stores before the profile that proves them arrives.
      const fg = component.formGroup();
      fg.controls.attributes.setValue('{"marca":"Patito"}');

      fixture.detectChanges();

      expect(fg.controls.attributes.value).toBe('{"marca":"Patito"}');
      expect(fieldSet()).toBeNull();
    });

    it('should keep the saved values when the host seeds the form in edit mode (S5)', () => {
      withProfile(
        profile([
          definition({ code: 'marca', label: 'Marca' }),
          definition({
            code: 'sistema_rosca',
            label: 'Sistema de rosca',
            kind: 'ENUM',
            values: [{ code: 'npt', label: 'NPT' }],
          }),
        ]),
      );

      const seeded = createFormGroup();
      seeded.controls.attributes.setValue('{"marca":"Patito","sistema_rosca":"npt"}');
      fixture.componentRef.setInput('formGroup', seeded);
      fixture.detectChanges();

      expect(component.attributeForm().get('marca')?.value).toBe('Patito');
      expect(component.attributeForm().get('sistema_rosca')?.value).toBe('npt');
      expect(savePayload()).toEqual({ marca: 'Patito', sistema_rosca: 'npt' });
    });

    it('should report the deepest selected category so the host can load its profile (S4)', () => {
      const spy = vi.fn();
      component.categoryChange.subscribe(spy);
      fixture.componentRef.setInput('categoryTree', categoryTree);
      fixture.detectChanges();

      component.onRootChange('root-a');
      expect(spy).toHaveBeenLastCalledWith('root-a');

      component.onSubcategory1Change('sub1-a');
      expect(spy).toHaveBeenLastCalledWith('sub1-a');

      component.onRootChange('');
      expect(spy).toHaveBeenLastCalledWith(null);
    });

    it('should report a saved path as the selected category too (S4)', () => {
      const spy = vi.fn();
      component.categoryChange.subscribe(spy);
      fixture.componentRef.setInput('categoryTree', categoryTree);
      fixture.componentRef.setInput('categoryPath', ['root-a', 'sub1-a']);
      fixture.detectChanges();

      expect(spy).toHaveBeenLastCalledWith('sub1-a');
    });
  });

  describe('category cascade (S1-S8)', () => {
    /** The form as a host renders it: the company's tree already loaded. */
    function withTree(): void {
      fixture.componentRef.setInput('categoryTree', categoryTree);
      fixture.detectChanges();
    }

    function labels(): string[] {
      return Array.from<Element>(fixture.nativeElement.querySelectorAll('mat-label')).map((label) =>
        (label.textContent ?? '').trim(),
      );
    }

    function bodyText(): string {
      return (fixture.nativeElement as HTMLElement).textContent ?? '';
    }

    it('should render the three category selects and the company select (S1, S2)', () => {
      withTree();

      expect(labels()).toEqual(
        expect.arrayContaining([
          'Empresa',
          'Categoría',
          'Subcategoría nivel 1',
          'Subcategoría nivel 2',
        ]),
      );
      // The three level selects are real controls of the form, not just labels.
      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(4);
    });

    describe('the Empresa field (S3)', () => {
      function empresaSelect(): MatSelect {
        return fixture.debugElement.queryAll(By.directive(MatSelect))[0]
          .componentInstance as MatSelect;
      }

      it('should keep the Empresa select disabled when the host says nothing', () => {
        // The default is closed on purpose: a host that does not say what it is editing must not hand out
        // a company choice, because the choice decides the tree and the endpoint of the save.
        withTree();

        expect(empresaSelect().disabled).toBe(true);
      });

      it('should enable the Empresa select on create and keep it disabled on edit', () => {
        fixture.componentRef.setInput('companyEditable', true);
        withTree();
        expect(empresaSelect().disabled).toBe(false);

        // Even asked to, an existing product's company stays fixed: the field could only contradict the
        // company that owns it.
        fixture.componentRef.setInput('editMode', true);
        fixture.detectChanges();
        expect(empresaSelect().disabled).toBe(true);
      });
    });

    it('should keep a pick across a change-detection flush (S1, S4)', () => {
      // The seeding effect writes the same controls a pick writes. While it also *read* the mirrored
      // selection it re-ran on every pick and re-applied the saved (empty) path on the next flush,
      // wiping the pick — the defect this pins.
      withTree();
      const fg = component.formGroup();

      component.onRootChange('root-a');
      fixture.detectChanges();
      fixture.detectChanges();

      expect(fg.controls.categoryRootId.value).toBe('root-a');
      expect(fg.controls.categoryId.value).toBe('root-a');
      expect(component.satCodeHint()).toBe('Código SAT tomado de: Alimentos');
    });

    it('should list only the children of the picked node and gate the deeper levels (S1, S7)', () => {
      withTree();
      const fg = component.formGroup();

      // Nothing picked yet: level 1 lists the roots and the levels below it are empty and disabled.
      expect(optionIds(component.categoryOptions())).toEqual(['root-a', 'root-b']);
      expect(optionIds(component.subcategory1Options())).toEqual([]);
      expect(fg.controls.subcategory1Id.disabled).toBe(true);
      expect(fg.controls.subcategory2Id.disabled).toBe(true);

      component.onRootChange('root-a');
      fixture.detectChanges();

      expect(optionIds(component.subcategory1Options())).toEqual(['sub1-a']);
      expect(optionIds(component.subcategory2Options())).toEqual([]);
      expect(fg.controls.subcategory1Id.enabled).toBe(true);
      expect(fg.controls.subcategory2Id.disabled).toBe(true);

      component.onSubcategory1Change('sub1-a');
      fixture.detectChanges();

      expect(optionIds(component.subcategory2Options())).toEqual(['sub2-a']);
      expect(fg.controls.subcategory2Id.enabled).toBe(true);
    });

    it('should list a disabled node of a saved path without letting it be picked (S8)', () => {
      withTree();

      component.onRootChange('root-b');
      fixture.detectChanges();

      // Listed, so a saved path renders, but not selectable: the node is disabled on the server.
      expect(optionIds(component.subcategory1Options())).toEqual(['sub1-b']);
      expect(component.subcategory1Options()[0].selectable).toBe(false);

      // The node the product is actually classified under stays selectable, or the picker would show
      // "empty" for a level the product really has.
      fixture.componentRef.setInput('categoryPath', ['root-b', 'sub1-b']);
      fixture.detectChanges();

      expect(component.subcategory1Options()[0].selectable).toBe(true);
    });

    it('should write the node code into the SAT input and say where it came from (S4)', () => {
      withTree();
      const fg = component.formGroup();

      component.onRootChange('root-a');
      fixture.detectChanges();

      expect(fg.controls.satCode.value).toBe('11111111');
      expect(component.satCodeHint()).toBe('Código SAT tomado de: Alimentos');
      expect(bodyText()).toContain('Código SAT tomado de: Alimentos');

      // A deeper node overwrites it and renames the source: the hint describes the last pick.
      component.onSubcategory1Change('sub1-a');
      component.onSubcategory2Change('sub2-a');
      fixture.detectChanges();

      expect(fg.controls.satCode.value).toBe('22222222');
      expect(component.satCodeHint()).toBe('Código SAT tomado de: Jugos');
    });

    it('should leave the SAT input untouched when the picked node has no code (S5)', () => {
      withTree();
      const fg = component.formGroup();
      fg.controls.satCode.setValue('99999999');

      component.onRootChange('root-b');
      fixture.detectChanges();

      expect(fg.controls.satCode.value).toBe('99999999');
      expect(component.satCodeHint()).toBeNull();
      expect(bodyText()).not.toContain('Código SAT tomado de:');

      // ... and neither does a deeper node without a key: the input keeps the previous suggestion,
      // and the hint that named the earlier node goes away because it described that pick (S5).
      component.onRootChange('root-a');
      component.onSubcategory1Change('sub1-a');
      fixture.detectChanges();

      expect(fg.controls.satCode.value).toBe('11111111');
      expect(component.satCodeHint()).toBeNull();
    });

    it('should clear the lower levels and the persisted category when a level changes (S7)', () => {
      withTree();
      const fg = component.formGroup();

      component.onRootChange('root-a');
      component.onSubcategory1Change('sub1-a');
      component.onSubcategory2Change('sub2-a');
      expect(fg.controls.categoryId.value).toBe('sub2-a');

      component.onRootChange('root-b');
      fixture.detectChanges();

      expect(fg.controls.subcategory1Id.value).toBe('');
      expect(fg.controls.subcategory2Id.value).toBe('');
      expect(fg.controls.categoryId.value).toBe('root-b');

      component.onRootChange('root-a');
      component.onSubcategory1Change('sub1-a');
      component.onSubcategory2Change('sub2-a');
      expect(fg.controls.categoryId.value).toBe('sub2-a');

      // Changing level 2 clears level 3, and the classification re-anchors to the deepest node that
      // is still selected (S3): dropping one level must not silently unclassify the product while a
      // category is still picked.
      component.onSubcategory1Change('');
      fixture.detectChanges();

      expect(fg.controls.subcategory2Id.value).toBe('');
      expect(fg.controls.categoryId.value).toBe('root-a');
    });

    it('should emit the deepest selected node as the category id (S3)', () => {
      const spy = vi.fn();
      component.saveProduct.subscribe(spy);
      withTree();

      const fg = component.formGroup();
      fg.patchValue({ sku: 'PROD-CAT', name: 'Producto con categoría' });
      component.onRootChange('root-a');
      component.onSubcategory1Change('sub1-a');
      component.onSubcategory2Change('sub2-a');
      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0].categoryId).toBe('sub2-a');
    });

    it('should emit a null category id when nothing is selected (S3)', () => {
      const spy = vi.fn();
      component.saveProduct.subscribe(spy);
      withTree();

      const fg = component.formGroup();
      fg.patchValue({ sku: 'PROD-NO-CAT', name: 'Producto sin categoría' });
      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0].categoryId).toBeNull();
    });

    it('should seed the three levels from the path without touching the SAT code (S6)', () => {
      const fg = component.formGroup();
      fg.controls.satCode.setValue('99999999');

      fixture.componentRef.setInput('categoryTree', categoryTree);
      fixture.componentRef.setInput('categoryPath', ['root-a', 'sub1-a', 'sub2-a']);
      fixture.detectChanges();

      expect(fg.controls.categoryRootId.value).toBe('root-a');
      expect(fg.controls.subcategory1Id.value).toBe('sub1-a');
      expect(fg.controls.subcategory2Id.value).toBe('sub2-a');
      expect(fg.controls.categoryId.value).toBe('sub2-a');
      // The saved code belongs to the product, not to the category: it is not rewritten...
      expect(fg.controls.satCode.value).toBe('99999999');
      // ... and the seeding is not a pick, so there is nothing to attribute.
      expect(component.satCodeHint()).toBeNull();
      expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
        'Código SAT tomado de:',
      );
      // The seeded levels are usable, so the operator can move the classification.
      expect(fg.controls.subcategory1Id.enabled).toBe(true);
      expect(fg.controls.subcategory2Id.enabled).toBe(true);
    });

    it('should leave the form untouched while the path is unknown (F1)', () => {
      // `null` is the host saying "my tree has not answered yet". Applying it would clear the
      // classification the product really has before the tree that proves it arrives.
      const fg = component.formGroup();
      fg.patchValue({ categoryId: 'sub2-a', satCode: '99999999' });

      fixture.componentRef.setInput('categoryTree', categoryTree);
      fixture.componentRef.setInput('categoryPath', null);
      fixture.detectChanges();

      expect(fg.controls.categoryId.value).toBe('sub2-a');
      expect(fg.controls.categoryRootId.value).toBe('');
      expect(fg.controls.subcategory1Id.value).toBe('');
      expect(fg.controls.subcategory2Id.value).toBe('');
      expect(fg.controls.satCode.value).toBe('99999999');
      expect(component.satCodeHint()).toBeNull();
    });
  });
});
