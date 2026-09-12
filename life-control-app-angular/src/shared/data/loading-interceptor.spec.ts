import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { loadingInterceptor } from './loading-interceptor';
import { LoadingService } from './loading';

const URL = 'http://localhost:9000/api/test';
const KEY = `GET:${URL}`;

describe('loadingInterceptor', () => {
  let loadingService: LoadingService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [LoadingService] });
    loadingService = TestBed.inject(LoadingService);
  });

  function pendingRequest(): { subject: Subject<HttpEvent<unknown>>; unsubscribe: () => void } {
    const subject = new Subject<HttpEvent<unknown>>();
    const next: HttpHandlerFn = () => subject.asObservable();
    const req = new HttpRequest('GET', URL);
    const stream = TestBed.runInInjectionContext(() => loadingInterceptor(req, next));
    const subscription = stream.subscribe({ error: () => undefined });

    return { subject, unsubscribe: () => subscription.unsubscribe() };
  }

  it('should start loading when a request starts', () => {
    expect(loadingService.isLoading()).toBe(false);

    const { subject, unsubscribe } = pendingRequest();

    expect(loadingService.isLoading()).toBe(true);
    expect(loadingService.isLoadingKey(KEY)).toBe(true);

    subject.complete();
    unsubscribe();
  });

  it('should stop loading when the request succeeds', () => {
    const { subject, unsubscribe } = pendingRequest();
    expect(loadingService.isLoading()).toBe(true);

    subject.next(new HttpResponse({ status: 200 }));
    subject.complete();
    unsubscribe();

    expect(loadingService.isLoading()).toBe(false);
    expect(loadingService.isLoadingKey(KEY)).toBe(false);
  });

  it('should stop loading when the request fails', () => {
    const { subject, unsubscribe } = pendingRequest();
    expect(loadingService.isLoading()).toBe(true);

    subject.error(new Error('request failed'));
    unsubscribe();

    expect(loadingService.isLoading()).toBe(false);
    expect(loadingService.isLoadingKey(KEY)).toBe(false);
  });
});
