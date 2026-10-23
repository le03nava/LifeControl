import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { ActivityLogPage } from '../models/activity-log.models';
import { ActivityLogService } from './activity-log.service';

const mockPage: ActivityLogPage = {
  content: [
    {
      id: 'log-1',
      userId: 'u-1',
      username: 'ana.gomez',
      process: 'HR',
      event: 'CREATE',
      httpMethod: 'POST',
      httpStatus: 201,
      requestPath: '/api/companies/c-1/employees',
      ipAddress: '10.0.0.7',
      userAgent: 'vitest',
      payloadJson: null,
      createdAt: '2026-01-02T03:04:05',
    },
  ],
  totalElements: 41,
  totalPages: 2,
  number: 2,
  size: 25,
  first: false,
  last: true,
};

describe('ActivityLogService', () => {
  let service: ActivityLogService;
  let httpMock: HttpTestingController;

  const baseUrl = 'http://localhost:9000/api/activity-logs';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        ActivityLogService,
        { provide: ConfigService, useValue: { apiUrl: 'http://localhost:9000/api' } },
      ],
    });
    service = TestBed.inject(ActivityLogService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should read GET /activity-logs with page, size and the six filters', async () => {
    const promise = firstValueFrom(
      service.search(2, 25, {
        from: '2026-01-01',
        to: '2026-01-31',
        process: 'HR',
        event: 'CREATE',
        userId: 'u-1',
        httpMethod: 'POST',
      }),
    );

    const req = httpMock.expectOne((request) => request.url === baseUrl);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('from')).toBe('2026-01-01');
    expect(req.request.params.get('to')).toBe('2026-01-31');
    expect(req.request.params.get('process')).toBe('HR');
    expect(req.request.params.get('event')).toBe('CREATE');
    expect(req.request.params.get('userId')).toBe('u-1');
    expect(req.request.params.get('httpMethod')).toBe('POST');

    req.flush(mockPage);
    await promise;
  });

  it('should omit every filter that is blank', async () => {
    const promise = firstValueFrom(
      service.search(0, 25, {
        from: '',
        to: '   ',
        process: '',
        event: '  ',
        userId: '',
        httpMethod: '',
      }),
    );

    const req = httpMock.expectOne((request) => request.url === baseUrl);
    expect(req.request.params.keys().sort()).toEqual(['page', 'size']);

    req.flush(mockPage);
    await promise;
  });

  it('should trim the values it does send', async () => {
    const promise = firstValueFrom(service.search(0, 10, { process: '  PROVISIONING  ' }));

    const req = httpMock.expectOne((request) => request.url === baseUrl);
    expect(req.request.params.get('process')).toBe('PROVISIONING');

    req.flush(mockPage);
    await promise;
  });

  it('should expose the page that was read and clear loading when the read settles', async () => {
    const promise = firstValueFrom(service.search(2, 25));

    expect(service.loading()).toBe(true);

    const req = httpMock.expectOne((request) => request.url === baseUrl);
    req.flush(mockPage);
    await promise;

    expect(service.page()).toEqual(mockPage);
    expect(service.loading()).toBe(false);
    expect(service.error()).toBeNull();
  });

  it('should expose the error, clear loading and rethrow when the read fails', async () => {
    const promise = firstValueFrom(service.search(0, 25));

    const req = httpMock.expectOne((request) => request.url === baseUrl);
    req.flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    await expect(promise).rejects.toBeTruthy();
    expect(service.error()).toBeTruthy();
    expect(service.loading()).toBe(false);
  });
});
