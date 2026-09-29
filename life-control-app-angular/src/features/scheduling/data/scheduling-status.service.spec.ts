import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { SchedulingStatusService } from './scheduling-status.service';

describe('SchedulingStatusService', () => {
  let service: SchedulingStatusService;
  let httpMock: HttpTestingController;

  const apiUrl = 'http://api.test/api';
  const statusTypesUrl = `${apiUrl}/status-types`;
  const statusesUrl = `${apiUrl}/statuses`;

  const appointmentType = { id: 'type-appointment', statusTypeName: 'APPOINTMENT' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [SchedulingStatusService, { provide: ConfigService, useValue: { apiUrl } }],
    });
    service = TestBed.inject(SchedulingStatusService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('loadAppointmentStatusIds', () => {
    it('should request the APPOINTMENT type with search=APPOINTMENT and the max page size', () => {
      service.loadAppointmentStatusIds().subscribe({ error: () => undefined });

      const req = httpMock.expectOne((r) => r.url === statusTypesUrl);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('search')).toBe('APPOINTMENT');
      // The deviation from D73: `search=` is a substring filter, so `size=1` could
      // return an unrelated type whose name merely contains APPOINTMENT. The app-wide
      // cap is `spring.data.web.pageable.max-page-size`.
      expect(req.request.params.get('size')).toBe('100');

      req.flush({ content: [appointmentType] });
      httpMock.expectOne((r) => r.url === statusesUrl).flush([]);
    });

    it('should carry the matched type id as statusTypeId on the second request', () => {
      service.loadAppointmentStatusIds().subscribe({ error: () => undefined });

      httpMock.expectOne((r) => r.url === statusTypesUrl).flush({ content: [appointmentType] });

      const statusesReq = httpMock.expectOne((r) => r.url === statusesUrl);
      expect(statusesReq.request.method).toBe('GET');
      expect(statusesReq.request.params.get('statusTypeId')).toBe('type-appointment');
      statusesReq.flush([]);
    });

    it('should match the type name case-insensitively', async () => {
      const promise = firstValueFrom(service.loadAppointmentStatusIds());

      httpMock
        .expectOne((r) => r.url === statusTypesUrl)
        .flush({ content: [{ id: 'type-lowercase', statusTypeName: 'appointment' }] });

      const statusesReq = httpMock.expectOne((r) => r.url === statusesUrl);
      expect(statusesReq.request.params.get('statusTypeId')).toBe('type-lowercase');
      statusesReq.flush([]);

      await expect(promise).resolves.toEqual(new Map());
    });

    it('should map statusName to id for every returned status, enabled or not', async () => {
      const promise = firstValueFrom(service.loadAppointmentStatusIds());

      httpMock.expectOne((r) => r.url === statusTypesUrl).flush({ content: [appointmentType] });

      httpMock
        .expectOne((r) => r.url === statusesUrl)
        .flush([
          { id: 'status-scheduled', statusName: 'Scheduled', enabled: true },
          { id: 'status-confirmed', statusName: 'Confirmed', enabled: true },
          { id: 'status-completed', statusName: 'Completed', enabled: true },
          { id: 'status-cancelled', statusName: 'Cancelled', enabled: true },
          { id: 'status-noshow', statusName: 'NoShow', enabled: false },
        ]);

      const map = await promise;
      expect(map.size).toBe(5);
      expect(map.get('Scheduled')).toBe('status-scheduled');
      expect(map.get('Confirmed')).toBe('status-confirmed');
      expect(map.get('Completed')).toBe('status-completed');
      expect(map.get('Cancelled')).toBe('status-cancelled');
      // `GET /statuses` answers disabled rows too (its `includeDisabled` is accepted
      // and never read), so the client must not filter on `enabled`.
      expect(map.get('NoShow')).toBe('status-noshow');
    });

    it('should fail and never resolve an empty map when no returned type matches exactly', async () => {
      const promise = firstValueFrom(service.loadAppointmentStatusIds());

      // The substring trap the max page size exists for: the type name *contains*
      // APPOINTMENT but is not APPOINTMENT.
      httpMock
        .expectOne((r) => r.url === statusTypesUrl)
        .flush({ content: [{ id: 'type-other', statusTypeName: 'APPOINTMENT_ARCHIVE' }] });

      await expect(promise).rejects.toThrow('APPOINTMENT status type not found');
    });

    it('should issue no second request when the first response does not match', async () => {
      const promise = firstValueFrom(service.loadAppointmentStatusIds());

      httpMock
        .expectOne((r) => r.url === statusTypesUrl)
        .flush({ content: [{ id: 'type-other', statusTypeName: 'APPOINTMENT_ARCHIVE' }] });

      await expect(promise).rejects.toThrow();
      expect(httpMock.match((r) => r.url === statusesUrl)).toHaveLength(0);
    });

    it('should fail when the first response carries no type at all', async () => {
      const promise = firstValueFrom(service.loadAppointmentStatusIds());

      httpMock.expectOne((r) => r.url === statusTypesUrl).flush({ content: [] });

      await expect(promise).rejects.toThrow('APPOINTMENT status type not found');
      expect(httpMock.match((r) => r.url === statusesUrl)).toHaveLength(0);
    });
  });
});
