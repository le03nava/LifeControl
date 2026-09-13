import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LoadingIndicator } from './loading-indicator';
import { LoadingService } from '../data/loading';

describe('LoadingIndicator', () => {
  let fixture: ComponentFixture<LoadingIndicator>;
  let service: LoadingService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LoadingIndicator],
    }).compileComponents();

    fixture = TestBed.createComponent(LoadingIndicator);
    service = TestBed.inject(LoadingService);
    fixture.detectChanges();
  });

  function overlay(): HTMLElement | null {
    return fixture.nativeElement.querySelector('.loading-overlay');
  }

  it('should be created', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('should NOT show the overlay when there is no active loading state', () => {
    expect(overlay()).toBeNull();
  });

  it('should show the overlay with a spinner and text when loading starts', () => {
    service.startLoading('load-regions');
    fixture.detectChanges();

    expect(overlay()).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.loading-overlay app-spinner')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.loading-text')?.textContent).toContain(
      'Loading...',
    );
  });

  it('should hide the overlay when the loading state stops', () => {
    service.startLoading('load-regions');
    fixture.detectChanges();
    expect(overlay()).toBeTruthy();

    service.stopLoading('load-regions');
    fixture.detectChanges();
    expect(overlay()).toBeNull();
  });

  it('should reflect the service isLoading signal (shared instance)', () => {
    expect(service.isLoading()).toBe(false);

    service.startLoading('http');
    fixture.detectChanges();
    expect(service.isLoading()).toBe(true);
    expect(overlay()).toBeTruthy();

    service.clearAll();
    fixture.detectChanges();
    expect(service.isLoading()).toBe(false);
    expect(overlay()).toBeNull();
  });
});
