import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { MeasureUnitService } from './measure-unit.service';
import { MeasureUnit } from '../models/product-variant.models';

describe('MeasureUnitService', () => {
  let service: MeasureUnitService;
  let httpMock: HttpTestingController;

  const measureUnitsUrl = 'http://localhost:9000/api/measure-units';

  const kilogram: MeasureUnit = {
    id: 'mu-kg',
    measureUnitName: 'Kilogramo',
    measureUnitShortName: 'Kg',
    unitType: 'PRODUCT',
    satCode: 'KGM',
    description: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const piece: MeasureUnit = {
    ...kilogram,
    id: 'mu-pza',
    measureUnitName: 'Pieza',
    measureUnitShortName: 'Pza',
    satCode: 'H87',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [MeasureUnitService],
    });
    service = TestBed.inject(MeasureUnitService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getMeasureUnits', () => {
    it('should GET the plain catalog array from /api/measure-units', async () => {
      const promise = firstValueFrom(service.getMeasureUnits());

      const req = httpMock.expectOne((r) => r.url === measureUnitsUrl && r.method === 'GET');
      // The endpoint answers with a plain array, not a page: no `page`/`size` params.
      expect(req.request.params.keys()).toEqual([]);
      req.flush([kilogram, piece]);

      const units = await promise;
      expect(Array.isArray(units)).toBe(true);
      expect(units.length).toBe(2);
      expect(units[0].measureUnitShortName).toBe('Kg');
      expect(units.map((unit) => unit.id)).toEqual(['mu-kg', 'mu-pza']);
    });

    it('should never ask for disabled units: the picker offers the enabled catalog', async () => {
      const promise = firstValueFrom(service.getMeasureUnits());

      const req = httpMock.expectOne(measureUnitsUrl);
      expect(req.request.params.has('includeDisabled')).toBe(false);
      req.flush([]);

      await expect(promise).resolves.toEqual([]);
    });

    it('should issue one request per call and keep no client-side cache', async () => {
      const first = firstValueFrom(service.getMeasureUnits());
      httpMock.expectOne(measureUnitsUrl).flush([kilogram]);
      await first;

      const second = firstValueFrom(service.getMeasureUnits());
      httpMock.expectOne(measureUnitsUrl).flush([kilogram, piece]);

      await expect(second).resolves.toHaveLength(2);
    });
  });
});
