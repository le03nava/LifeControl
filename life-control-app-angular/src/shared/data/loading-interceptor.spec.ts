import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { LOADING_INDICATOR_DELAY_MS, loadingInterceptor } from './loading-interceptor';
import { LoadingService } from './loading';

const URL = 'http://localhost:9000/api/test';
const KEY = `GET:${URL}`;

describe('loadingInterceptor', () => {
  let loadingService: LoadingService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [LoadingService] });
    loadingService = TestBed.inject(LoadingService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function pendingRequest(): { subject: Subject<HttpEvent<unknown>>; unsubscribe: () => void } {
    const subject = new Subject<HttpEvent<unknown>>();
    const next: HttpHandlerFn = () => subject.asObservable();
    const req = new HttpRequest('GET', URL);
    const stream = TestBed.runInInjectionContext(() => loadingInterceptor(req, next));
    const subscription = stream.subscribe({ error: () => undefined });

    return { subject, unsubscribe: () => subscription.unsubscribe() };
  }

  // The overlay is full-screen and opaque, so starting it for a request that
  // resolves in a few milliseconds paints the whole app white for a frame. That
  // instantaneous blank is the defect these tests exist to hold out.
  describe('requests faster than the delay', () => {
    it('should not start loading while the request is still under the delay', () => {
      expect(loadingService.isLoading()).toBe(false);

      const { subject, unsubscribe } = pendingRequest();

      expect(loadingService.isLoading()).toBe(false);
      expect(loadingService.isLoadingKey(KEY)).toBe(false);

      subject.complete();
      unsubscribe();
    });

    it('should never start loading when the request completes before the delay', () => {
      const { subject, unsubscribe } = pendingRequest();

      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS - 1);
      expect(loadingService.isLoading()).toBe(false);

      subject.next(new HttpResponse({ status: 200 }));
      subject.complete();
      unsubscribe();

      // The request is over. A timer that outlived it would paint the overlay on
      // an idle screen — a worse flash than the one being removed.
      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS * 2);
      expect(loadingService.isLoading()).toBe(false);
      expect(loadingService.isLoadingKey(KEY)).toBe(false);
    });

    it('should never start loading when a fast request fails', () => {
      const { subject, unsubscribe } = pendingRequest();

      subject.error(new Error('request failed'));
      unsubscribe();

      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS * 2);
      expect(loadingService.isLoading()).toBe(false);
      expect(loadingService.isLoadingKey(KEY)).toBe(false);
    });

    it('should never start loading when a fast request is cancelled', () => {
      const { unsubscribe } = pendingRequest();

      unsubscribe();

      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS * 2);
      expect(loadingService.isLoading()).toBe(false);
      // `finalize` also runs on unsubscribe, so a key left behind here would mean the
      // timer outlived the request that owned it.
      expect(loadingService.isLoadingKey(KEY)).toBe(false);
    });
  });

  // Past the threshold the indicator exists to tell the operator that something
  // is genuinely taking time, so its behaviour there must not change.
  describe('requests slower than the delay', () => {
    it('should start loading once the request passes the delay', () => {
      const { subject, unsubscribe } = pendingRequest();

      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS);

      expect(loadingService.isLoading()).toBe(true);
      expect(loadingService.isLoadingKey(KEY)).toBe(true);

      subject.complete();
      unsubscribe();
    });

    it('should stop loading when the request succeeds', () => {
      const { subject, unsubscribe } = pendingRequest();

      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS);
      expect(loadingService.isLoading()).toBe(true);

      subject.next(new HttpResponse({ status: 200 }));
      subject.complete();
      unsubscribe();

      expect(loadingService.isLoading()).toBe(false);
      expect(loadingService.isLoadingKey(KEY)).toBe(false);
    });

    it('should stop loading when the request fails', () => {
      const { subject, unsubscribe } = pendingRequest();

      vi.advanceTimersByTime(LOADING_INDICATOR_DELAY_MS);
      expect(loadingService.isLoading()).toBe(true);

      subject.error(new Error('request failed'));
      unsubscribe();

      expect(loadingService.isLoading()).toBe(false);
      expect(loadingService.isLoadingKey(KEY)).toBe(false);
    });
  });
});
