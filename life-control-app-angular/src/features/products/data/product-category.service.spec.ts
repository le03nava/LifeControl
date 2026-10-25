import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { ProductCategoryService } from './product-category.service';
import { ProductCategory, ProductCategoryRequest } from '../models/product-category.models';

describe('ProductCategoryService', () => {
  let service: ProductCategoryService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const baseUrl = 'http://localhost:9000/api';
  const categoriesUrl = `${baseUrl}/companies/${companyId}/product-categories`;

  const root: ProductCategory = {
    id: 'cat-1',
    companyId,
    parentCategoryId: null,
    categoryName: 'Bebidas',
    description: null,
    displayOrder: 0,
    depth: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const child: ProductCategory = {
    ...root,
    id: 'cat-2',
    parentCategoryId: 'cat-1',
    categoryName: 'Gaseosas',
    depth: 2,
  };

  const tree: ProductCategory[] = [root, child];

  const rootRequest: ProductCategoryRequest = {
    parentCategoryId: null,
    categoryName: 'Bebidas',
    description: null,
    displayOrder: 0,
    enabled: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        ProductCategoryService,
        { provide: ConfigService, useValue: { apiUrl: baseUrl } },
      ],
    });
    service = TestBed.inject(ProductCategoryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getCategories', () => {
    it('should read the company tree with includeDisabled false by default', async () => {
      const promise = firstValueFrom(service.getCategories(companyId));

      const req = httpMock.expectOne((r) => r.url === categoriesUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('false');
      req.flush(tree);

      expect(await promise).toEqual(tree);
    });

    it('should forward includeDisabled true so the toggle reaches the request', async () => {
      const promise = firstValueFrom(service.getCategories(companyId, true));

      const req = httpMock.expectOne((r) => r.url === categoriesUrl && r.method === 'GET');
      expect(req.request.params.get('includeDisabled')).toBe('true');
      req.flush(tree);

      expect(await promise).toEqual(tree);
    });
  });

  describe('getCategoryById', () => {
    it('should read one category of the company', async () => {
      const promise = firstValueFrom(service.getCategoryById(companyId, 'cat-2'));

      const req = httpMock.expectOne(
        (r) => r.url === `${categoriesUrl}/cat-2` && r.method === 'GET',
      );
      req.flush(child);

      expect(await promise).toEqual(child);
    });
  });

  describe('createCategory', () => {
    it('should post a root category without a parent', async () => {
      const promise = firstValueFrom(service.createCategory(companyId, rootRequest));

      const req = httpMock.expectOne((r) => r.url === categoriesUrl && r.method === 'POST');
      expect(req.request.body).toEqual(rootRequest);
      expect(req.request.body.parentCategoryId).toBeNull();
      req.flush(root, { status: 201, statusText: 'Created' });

      expect(await promise).toEqual(root);
    });

    it('should post a subcategory under its parent', async () => {
      const request: ProductCategoryRequest = {
        parentCategoryId: 'cat-1',
        categoryName: 'Gaseosas',
        description: 'Con azúcar y sin azúcar',
        displayOrder: 2,
        enabled: true,
      };

      const promise = firstValueFrom(service.createCategory(companyId, request));

      const req = httpMock.expectOne((r) => r.url === categoriesUrl && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush(child, { status: 201, statusText: 'Created' });

      expect(await promise).toEqual(child);
    });
  });

  describe('updateCategory', () => {
    it('should put the category with the payload it was given, enabled included', async () => {
      // F4: the server defaults an absent `enabled` to true and cascades a false one, so the
      // payload the caller built is forwarded verbatim — nothing here may drop the flag.
      const request: ProductCategoryRequest = {
        parentCategoryId: null,
        categoryName: 'Bebidas sin alcohol',
        description: null,
        displayOrder: 5,
        enabled: false,
      };

      const promise = firstValueFrom(service.updateCategory(companyId, 'cat-1', request));

      const req = httpMock.expectOne(
        (r) => r.url === `${categoriesUrl}/cat-1` && r.method === 'PUT',
      );
      expect(req.request.body).toEqual(request);
      expect(req.request.body.enabled).toBe(false);
      req.flush({ ...root, categoryName: request.categoryName, enabled: false });

      expect((await promise).enabled).toBe(false);
    });
  });

  describe('deleteCategory', () => {
    it('should soft-delete the category and its subtree by id', async () => {
      const promise = firstValueFrom(service.deleteCategory(companyId, 'cat-1'));

      const req = httpMock.expectOne(
        (r) => r.url === `${categoriesUrl}/cat-1` && r.method === 'DELETE',
      );
      req.flush(null, { status: 204, statusText: 'No Content' });

      expect(await promise).toBeNull();
    });
  });

  describe('setCategoryEnabled', () => {
    it('should patch the explicit true flag when enabling', async () => {
      const promise = firstValueFrom(service.setCategoryEnabled(companyId, 'cat-1', true));

      const req = httpMock.expectOne(
        (r) => r.url === `${categoriesUrl}/cat-1/enable` && r.method === 'PATCH',
      );
      expect(req.request.body).toEqual({ enabled: true });
      req.flush(root);

      expect((await promise).enabled).toBe(true);
    });

    it('should patch the explicit false flag when disabling', async () => {
      const promise = firstValueFrom(service.setCategoryEnabled(companyId, 'cat-1', false));

      const req = httpMock.expectOne(
        (r) => r.url === `${categoriesUrl}/cat-1/enable` && r.method === 'PATCH',
      );
      expect(req.request.body).toEqual({ enabled: false });
      req.flush({ ...root, enabled: false });

      expect((await promise).enabled).toBe(false);
    });
  });
});
