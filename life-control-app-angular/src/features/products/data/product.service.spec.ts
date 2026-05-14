import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ProductService } from './product.service';
import { ConfigService } from '@app/services/config.service';
import { Page } from '../models/product.models';

describe('ProductService', () => {
  let service: ProductService;
  let httpMock: HttpTestingController;
  let configService: jasmine.SpyObj<ConfigService>;

  beforeEach(() => {
    const configSpy = jasmine.createSpyObj('ConfigService', [], {
      apiUrl: 'http://localhost:9000',
    });

    TestBed.configureTestingModule({
      providers: [
        ProductService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ConfigService, useValue: configSpy },
      ],
    });

    service = TestBed.inject(ProductService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getProductsPaged', () => {
    it('should fetch paginated products with page and size params', () => {
      const mockPage: Page<{ id: string; name: string; description: string; price: number }> = {
        content: [
          { id: '1', name: 'Product 1', description: 'Desc 1', price: 100 },
          { id: '2', name: 'Product 2', description: 'Desc 2', price: 200 },
        ],
        totalElements: 10,
        totalPages: 5,
        number: 0,
        size: 2,
        first: true,
        last: false,
        empty: false,
      };

      service.getProductsPaged(0, 2).subscribe((page) => {
        expect(page.content.length).toBe(2);
        expect(page.totalElements).toBe(10);
        expect(page.number).toBe(0);
      });

      const req = httpMock.expectOne(
        (request) =>
          request.url === 'http://localhost:9000/products' &&
          request.params.get('page') === '0' &&
          request.params.get('size') === '2',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockPage);
    });

    it('should include search param when provided', () => {
      service.getProductsPaged(0, 12, 'test').subscribe();

      const req = httpMock.expectOne(
        (request) =>
          request.url === 'http://localhost:9000/products' &&
          request.params.get('page') === '0' &&
          request.params.get('size') === '12' &&
          request.params.get('search') === 'test',
      );
      expect(req.request.method).toBe('GET');
      req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 12, first: true, last: true, empty: true });
    });

    it('should not include search param when search is undefined', () => {
      service.getProductsPaged(1, 24).subscribe();

      const req = httpMock.expectOne(
        (request) =>
          request.url === 'http://localhost:9000/products' &&
          request.params.get('page') === '1' &&
          request.params.get('size') === '24' &&
          request.params.get('search') === null,
      );
      expect(req.request.method).toBe('GET');
      req.flush({ content: [], totalElements: 0, totalPages: 0, number: 1, size: 24, first: false, last: true, empty: true });
    });
  });
});
