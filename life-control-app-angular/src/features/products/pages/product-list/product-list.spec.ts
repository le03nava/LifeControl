import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ProductList } from './product-list';
import { ProductService } from '@features/products/data/product.service';
import { of } from 'rxjs';
import { Page } from '@features/products/models/product.models';

describe('ProductList', () => {
  let component: ProductList;
  let fixture: ComponentFixture<ProductList>;
  let productService: jasmine.SpyObj<ProductService>;

  const mockPage: Page<{ id: string; name: string; description: string; price: number }> = {
    content: [
      { id: '1', name: 'Product 1', description: 'Description 1', price: 100 },
      { id: '2', name: 'Product 2', description: 'Description 2', price: 200 },
    ],
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 12,
    first: true,
    last: true,
    empty: false,
  };

  beforeEach(async () => {
    const serviceSpy = jasmine.createSpyObj('ProductService', ['getProductsPaged', 'deleteProduct']);
    serviceSpy.getProductsPaged.and.returnValue(of(mockPage));

    await TestBed.configureTestingModule({
      imports: [ProductList],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ProductService, useValue: serviceSpy },
      ],
    }).compileComponents();

    productService = TestBed.inject(ProductService) as jasmine.SpyObj<ProductService>;
    fixture = TestBed.createComponent(ProductList);
    component = fixture.componentRef.instance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load products on init', () => {
    expect(productService.getProductsPaged).toHaveBeenCalledWith(0, 12, undefined);
  });

  it('should have default page values', () => {
    expect(component.pageIndex()).toBe(0);
    expect(component.pageSize()).toBe(12);
    expect(component.searchQuery()).toBe('');
  });

  it('should update pageIndex and pageSize on page change', () => {
    component.onPageChange({ pageIndex: 2, pageSize: 24, length: 50 } as any);
    expect(component.pageIndex()).toBe(2);
    expect(component.pageSize()).toBe(24);
  });

  it('should update search query on input', () => {
    component.searchQuery.set('test');
    expect(component.searchQuery()).toBe('test');
  });

  it('should clear search query on clearSearch', () => {
    component.searchQuery.set('test');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  it('should set delete modal state on confirmDelete', () => {
    component.confirmDelete({ id: '1', name: 'Product 1' });
    expect(component.showDeleteModal()).toBeTrue();
    expect(component.productToDelete()).toEqual({ id: '1', name: 'Product 1' });
  });

  it('should clear delete modal state on cancelDelete', () => {
    component.confirmDelete({ id: '1', name: 'Product 1' });
    component.cancelDelete();
    expect(component.showDeleteModal()).toBeFalse();
    expect(component.productToDelete()).toBeNull();
  });

  it('should call deleteProduct and reload on executeDelete', () => {
    productService.deleteProduct.and.returnValue(of(void 0));
    component.confirmDelete({ id: '1', name: 'Product 1' });
    component.executeDelete();

    expect(productService.deleteProduct).toHaveBeenCalledWith('1');
    expect(component.showDeleteModal()).toBeFalse();
    expect(component.productToDelete()).toBeNull();
  });
});
