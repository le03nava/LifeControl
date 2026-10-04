import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ContractService } from './contract.service';
import { ConfigService } from '@app/services/config.service';
import {
  Contract,
  ContractRequest,
  Position,
  PositionSalaryBand,
  SeniorityLevel,
} from '../models/contract.models';

describe('ContractService', () => {
  let service: ContractService;
  let httpMock: HttpTestingController;

  const companyId = 'company-123';
  const employeeId = 'employee-456';
  const baseUrl = 'http://localhost:9000/api';
  const contractsUrl = `${baseUrl}/companies/${companyId}/employees/${employeeId}/contracts`;

  const mockContracts: Contract[] = [
    {
      id: 'contract-2',
      employeeId,
      positionId: 'position-1',
      positionName: 'Analista',
      seniorityLevelId: 'level-2',
      seniorityLevelName: 'Semi senior',
      contractType: 'FIXED_TERM',
      monthlySalary: 25000,
      startDate: '2026-01-01',
      endDate: '2026-06-30',
      enabled: true,
    },
    {
      id: 'contract-1',
      employeeId,
      positionId: 'position-1',
      positionName: 'Analista',
      seniorityLevelId: 'level-1',
      seniorityLevelName: 'Junior',
      contractType: 'PERMANENT',
      monthlySalary: 20000,
      startDate: '2024-01-01',
      endDate: null,
      enabled: false,
    },
  ];

  const request: ContractRequest = {
    positionId: 'position-1',
    seniorityLevelId: 'level-2',
    contractType: 'FIXED_TERM',
    monthlySalary: 25000,
    startDate: '2026-01-01',
    endDate: null,
  };

  const mockPosition: Position = {
    id: 'position-1',
    companyId,
    departmentId: 'department-1',
    positionCode: 'ANA-01',
    positionName: 'Analista',
    description: null,
    reportsToPositionId: null,
    displayOrder: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const mockSeniorityLevel: SeniorityLevel = {
    id: 'level-2',
    levelCode: 'SR2',
    levelName: 'Semi senior',
    rank: 2,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const mockSalaryBand: PositionSalaryBand = {
    id: 'band-1',
    positionId: 'position-1',
    seniorityLevelId: 'level-2',
    minimumSalary: 20000,
    maximumSalary: 30000,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ContractService, { provide: ConfigService, useValue: { apiUrl: baseUrl } }],
    });
    service = TestBed.inject(ContractService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  /** Seeds the contracts signal through a real list read. */
  async function seedContracts(): Promise<void> {
    const listPromise = firstValueFrom(service.getContracts(companyId, employeeId));
    httpMock.expectOne((r) => r.url === contractsUrl && r.method === 'GET').flush(mockContracts);
    await listPromise;
  }

  describe('getContracts', () => {
    it('should GET the employee contracts and expose them on the signal', async () => {
      const promise = firstValueFrom(service.getContracts(companyId, employeeId));

      const req = httpMock.expectOne((r) => r.url === contractsUrl && r.method === 'GET');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.keys()).toEqual([]);
      req.flush(mockContracts);

      await expect(promise).resolves.toEqual(mockContracts);
      expect(service.contracts()).toEqual(mockContracts);
    });

    it('should set loading true during the fetch and false after', async () => {
      expect(service.loading()).toBe(false);

      const promise = firstValueFrom(service.getContracts(companyId, employeeId));
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(service.loading()).toBe(true);

      httpMock.expectOne((r) => r.url === contractsUrl).flush(mockContracts);
      await promise;
      expect(service.loading()).toBe(false);
    });

    it('should set the error signal on failure and re-throw', async () => {
      const promise = firstValueFrom(service.getContracts(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === contractsUrl)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al cargar los contratos');
    });

    it('should clear the error signal on a successful read after a failure', async () => {
      const failing = firstValueFrom(service.getContracts(companyId, employeeId));
      httpMock
        .expectOne((r) => r.url === contractsUrl && r.method === 'GET')
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      await expect(failing).rejects.toBeTruthy();
      expect(service.error()).toBe('Error al cargar los contratos');

      const succeeding = firstValueFrom(service.getContracts(companyId, employeeId));
      httpMock.expectOne((r) => r.url === contractsUrl && r.method === 'GET').flush(mockContracts);
      await succeeding;

      expect(service.error()).toBeNull();
    });
  });

  describe('addContract', () => {
    it('should POST the request and prepend the created contract', async () => {
      await seedContracts();

      const created: Contract = {
        ...mockContracts[0],
        id: 'contract-3',
        startDate: '2026-07-01',
        endDate: null,
      };
      const promise = firstValueFrom(service.addContract(companyId, employeeId, request));

      const req = httpMock.expectOne((r) => r.url === contractsUrl && r.method === 'POST');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(created, { status: 201, statusText: 'Created' });

      await expect(promise).resolves.toEqual(created);
      // The API orders newest first, so a freshly created contract goes to the front.
      expect(service.contracts()).toEqual([created, ...mockContracts]);
    });

    it('should set the error signal on failure and re-throw', async () => {
      const promise = firstValueFrom(service.addContract(companyId, employeeId, request));
      httpMock
        .expectOne((r) => r.url === contractsUrl && r.method === 'POST')
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });

      await expect(promise).rejects.toMatchObject({ status: 409 });
      expect(service.error()).toBe('Error al crear el contrato');
    });
  });

  describe('closeContract', () => {
    it('should PATCH close with an inclusive endDate and replace the row', async () => {
      await seedContracts();

      const closed: Contract = { ...mockContracts[0], endDate: '2026-06-15' };
      const promise = firstValueFrom(
        service.closeContract(companyId, employeeId, 'contract-2', '2026-06-15'),
      );

      const req = httpMock.expectOne(`${contractsUrl}/contract-2/close`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ endDate: '2026-06-15' });
      req.flush(closed);

      await promise;
      expect(service.contracts().find((c) => c.id === 'contract-2')).toEqual(closed);
    });

    it('should send no body when the endDate is omitted, so the API closes today', async () => {
      await seedContracts();

      const closed: Contract = { ...mockContracts[0], endDate: '2026-06-30' };
      const promise = firstValueFrom(service.closeContract(companyId, employeeId, 'contract-2'));

      const req = httpMock.expectOne(`${contractsUrl}/contract-2/close`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toBeNull();
      req.flush(closed);

      await promise;
      expect(service.contracts().find((c) => c.id === 'contract-2')).toEqual(closed);
    });

    it('should set the error signal on failure and re-throw', async () => {
      const promise = firstValueFrom(service.closeContract(companyId, employeeId, 'contract-2'));
      httpMock
        .expectOne(`${contractsUrl}/contract-2/close`)
        .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });

      await expect(promise).rejects.toMatchObject({ status: 500 });
      expect(service.error()).toBe('Error al cerrar el contrato');
    });
  });

  describe('catalog lookups', () => {
    it('getPositions should GET the company positions and mutate no state', async () => {
      await seedContracts();

      const promise = firstValueFrom(service.getPositions(companyId));

      const req = httpMock.expectOne(
        (r) => r.url === `${baseUrl}/companies/${companyId}/positions` && r.method === 'GET',
      );
      expect(req.request.method).toBe('GET');
      req.flush([mockPosition]);

      await expect(promise).resolves.toEqual([mockPosition]);
      expect(service.contracts()).toEqual(mockContracts);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });

    it('getSeniorityLevels should GET the global endpoint with no companyId', async () => {
      const promise = firstValueFrom(service.getSeniorityLevels());

      const req = httpMock.expectOne(
        (r) => r.url === `${baseUrl}/seniority-levels` && r.method === 'GET',
      );
      expect(req.request.method).toBe('GET');
      // Global reference data: the path carries no company segment at all.
      expect(req.request.url).not.toContain('companies');
      expect(req.request.url).not.toContain(companyId);
      req.flush([mockSeniorityLevel]);

      await expect(promise).resolves.toEqual([mockSeniorityLevel]);
    });

    it('getSalaryBands should GET the position salary bands and mutate no state', async () => {
      const promise = firstValueFrom(service.getSalaryBands(companyId, 'position-1'));

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${baseUrl}/companies/${companyId}/positions/position-1/salary-bands` &&
          r.method === 'GET',
      );
      expect(req.request.method).toBe('GET');
      req.flush([mockSalaryBand]);

      await expect(promise).resolves.toEqual([mockSalaryBand]);
      expect(service.loading()).toBe(false);
      expect(service.error()).toBeNull();
    });
  });

  it('clearError should reset the error signal', async () => {
    const promise = firstValueFrom(service.getContracts(companyId, employeeId));
    httpMock
      .expectOne((r) => r.url === contractsUrl)
      .flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
    await expect(promise).rejects.toBeTruthy();

    service.clearError();
    expect(service.error()).toBeNull();
  });
});
