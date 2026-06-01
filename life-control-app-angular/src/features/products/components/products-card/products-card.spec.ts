import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ProductsCard } from './products-card';
import { Product } from '../../models/product.models';

describe('ProductsCard', () => {
  let component: ProductsCard;
  let fixture: ComponentFixture<ProductsCard>;

  const mockProduct: Product = {
    id: '1',
    sku: 'PROD-001',
    name: 'Producto de prueba',
    shortName: 'Test',
    satCode: '123456',
    productType: 'Servicio',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductsCard, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductsCard);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('product', mockProduct);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display product name and SKU', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Producto de prueba');
    expect(el.textContent).toContain('PROD-001');
  });

  it('should display status chip as active', () => {
    const el = fixture.nativeElement as HTMLElement;
    const statusBadge = el.querySelector('.badge.status');
    expect(statusBadge).toBeTruthy();
    expect(statusBadge?.textContent).toContain('Activo');
  });

  it('should display productType badge', () => {
    const el = fixture.nativeElement as HTMLElement;
    const typeBadge = el.querySelector('.badge.type-badge');
    expect(typeBadge).toBeTruthy();
    expect(typeBadge?.textContent).toContain('Servicio');
  });

  it('should emit editProduct when edit button is clicked', () => {
    const spy = vi.fn();
    component.editProduct.subscribe(spy);

    const editBtn = (fixture.nativeElement as HTMLElement).querySelector(
      '[aria-label="Editar producto"]',
    );
    expect(editBtn).toBeTruthy();
    (editBtn as HTMLButtonElement).click();

    expect(spy).toHaveBeenCalledWith(mockProduct);
  });

  it('should emit deleteProduct when delete button is clicked', () => {
    const spy = vi.fn();
    component.deleteProduct.subscribe(spy);

    const deleteBtn = (fixture.nativeElement as HTMLElement).querySelector(
      '[aria-label="Eliminar producto"]',
    );
    expect(deleteBtn).toBeTruthy();
    (deleteBtn as HTMLButtonElement).click();

    expect(spy).toHaveBeenCalledWith(mockProduct);
  });

  it('should show inactive status when product is disabled', () => {
    fixture.componentRef.setInput('product', { ...mockProduct, enabled: false });
    fixture.detectChanges();

    const statusBadge = (fixture.nativeElement as HTMLElement).querySelector('.badge.status');
    expect(statusBadge?.textContent).toContain('Inactivo');
  });

  it('should hide productType badge when productType is undefined', () => {
    fixture.componentRef.setInput('product', { ...mockProduct, productType: undefined });
    fixture.detectChanges();

    const typeBadge = (fixture.nativeElement as HTMLElement).querySelector('.badge.type-badge');
    expect(typeBadge).toBeNull();
  });
});
