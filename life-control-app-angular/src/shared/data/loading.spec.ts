import { TestBed } from '@angular/core/testing';
import { LoadingService } from './loading';

describe('LoadingService', () => {
  let service: LoadingService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [LoadingService] });
    service = TestBed.inject(LoadingService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should start with no active loading states', () => {
    expect(service.isLoading()).toBe(false);
    expect(service.loadingCount()).toBe(0);
  });

  describe('startLoading', () => {
    it('should increment the counter and set isLoading to true', () => {
      service.startLoading('load-users');

      expect(service.loadingCount()).toBe(1);
      expect(service.isLoading()).toBe(true);
      expect(service.isLoadingKey('load-users')).toBe(true);
    });

    it('should be idempotent for the same key', () => {
      service.startLoading('load-users');
      service.startLoading('load-users');

      expect(service.loadingCount()).toBe(1);
      expect(service.isLoading()).toBe(true);
    });
  });

  describe('stopLoading', () => {
    it('should decrement the counter and set isLoading to false when it reaches 0', () => {
      service.startLoading('load-users');
      service.startLoading('load-companies');
      expect(service.loadingCount()).toBe(2);
      expect(service.isLoading()).toBe(true);

      service.stopLoading('load-users');

      expect(service.loadingCount()).toBe(1);
      expect(service.isLoading()).toBe(true);

      service.stopLoading('load-companies');

      expect(service.loadingCount()).toBe(0);
      expect(service.isLoading()).toBe(false);
      expect(service.isLoadingKey('load-companies')).toBe(false);
    });

    it('should not error when stopping a key that was never started', () => {
      expect(() => service.stopLoading('unknown-key')).not.toThrow();
      expect(service.loadingCount()).toBe(0);
      expect(service.isLoading()).toBe(false);
    });
  });

  describe('nested start/stop', () => {
    it('should handle multiple nested loading states correctly', () => {
      service.startLoading('http');
      service.startLoading('image');
      service.startLoading('route');

      expect(service.loadingCount()).toBe(3);
      expect(service.isLoading()).toBe(true);

      service.stopLoading('image');

      expect(service.loadingCount()).toBe(2);
      expect(service.isLoading()).toBe(true);
      expect(service.isLoadingKey('http')).toBe(true);
      expect(service.isLoadingKey('route')).toBe(true);
      expect(service.isLoadingKey('image')).toBe(false);

      service.stopLoading('http');
      service.stopLoading('route');

      expect(service.loadingCount()).toBe(0);
      expect(service.isLoading()).toBe(false);
    });
  });

  describe('clearAll', () => {
    it('should reset all loading states', () => {
      service.startLoading('a');
      service.startLoading('b');

      service.clearAll();

      expect(service.loadingCount()).toBe(0);
      expect(service.isLoading()).toBe(false);
    });
  });
});
