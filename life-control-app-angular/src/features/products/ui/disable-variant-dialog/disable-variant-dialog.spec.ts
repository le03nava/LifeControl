import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { DisableVariantDialogComponent } from './disable-variant-dialog';
import { ProductVariant } from '../../models/product-variant.models';

describe('DisableVariantDialogComponent', () => {
  let component: DisableVariantDialogComponent;
  let fixture: ComponentFixture<DisableVariantDialogComponent>;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const mockVariant: ProductVariant = {
    id: 'var-1',
    productId: 'prod-1',
    companyStoreId: null,
    barCode: '7790000000012',
    sku: 'SKU-001',
    variantName: 'Talla 38',
    listPrice: null,
    costPrice: null,
    stock: null,
    enabled: true,
  };

  const mockData = { variant: mockVariant };

  beforeEach(async () => {
    dialogRef = { close: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [DisableVariantDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: mockData },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DisableVariantDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should receive the variant from data', () => {
    expect(component.data.variant).toBe(mockVariant);
  });

  it('should render the variant name and barcode in template', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Talla 38');
    expect(el.textContent).toContain('7790000000012');
  });

  it('should describe the action as disabling, never deleting', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('deshabilitar');
    expect(el.textContent).toContain('volver a habilitarla');
    expect(el.textContent).not.toContain('Eliminar');
    expect(el.textContent).not.toContain('eliminar');
  });

  it('should render cancel and disable buttons', () => {
    const el = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toContain('Cancelar');
    expect(buttons[1].textContent).toContain('Deshabilitar');
  });
});
