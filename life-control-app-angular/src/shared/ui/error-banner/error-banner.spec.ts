import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ErrorBanner } from './error-banner';

describe('ErrorBanner', () => {
  let fixture: ComponentFixture<ErrorBanner>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ErrorBanner],
    }).compileComponents();

    fixture = TestBed.createComponent(ErrorBanner);
    fixture.detectChanges();
  });

  it('should be created', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('should render the banner as an alert region', () => {
    const banner = fixture.nativeElement.querySelector('.error-banner');
    expect(banner).toBeTruthy();
    expect(banner.getAttribute('role')).toBe('alert');
  });

  it('should render an error icon', () => {
    expect(fixture.nativeElement.querySelector('.error-banner mat-icon')).toBeTruthy();
  });

  it('should show the error message when provided', () => {
    fixture.componentRef.setInput('message', 'No se pudo cargar la empresa');
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('.error-banner');
    expect(banner.textContent).toContain('No se pudo cargar la empresa');
  });

  it('should update the message reactively', () => {
    fixture.componentRef.setInput('message', 'Primer error');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.error-banner').textContent).toContain(
      'Primer error',
    );

    fixture.componentRef.setInput('message', 'Segundo error');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.error-banner').textContent).toContain(
      'Segundo error',
    );
  });

  it('should render an empty message span when message is null', () => {
    fixture.componentRef.setInput('message', null);
    fixture.detectChanges();

    const span = fixture.nativeElement.querySelector('.error-banner span');
    expect(span).toBeTruthy();
    expect(span.textContent).toBe('');
  });
});
