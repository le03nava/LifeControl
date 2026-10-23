import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

  // ─── Opt-in scroll into view ────────────────────────────────
  //
  // The banner is used by more than thirty templates, most of them inside short dialogs and lists
  // where the banner is already on screen. Scrolling is therefore opt-in: a page whose save button
  // sits below a long form asks for it, and every other consumer keeps the behaviour it has today.
  describe('scroll into view', () => {
    let scrollIntoView: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
      scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView');
    });

    afterEach(() => {
      vi.restoreAllMocks();
    });

    it('should not scroll by default when a message appears', () => {
      fixture.componentRef.setInput('message', 'No se pudo guardar');
      fixture.detectChanges();

      expect(scrollIntoView).not.toHaveBeenCalled();
    });

    it('should scroll the banner into view when opted in and a message appears', () => {
      fixture.componentRef.setInput('autoScroll', true);
      fixture.componentRef.setInput('message', 'Otra sesión modificó esta tienda');
      fixture.detectChanges();

      expect(scrollIntoView).toHaveBeenCalledTimes(1);
      // `nearest` is the minimal movement that makes the banner visible: it does nothing when the
      // banner is already on screen and never pushes the form out of view to centre it.
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
      expect(scrollIntoView.mock.contexts[0]).toBe(fixture.nativeElement);
    });

    it('should not scroll when opted in but there is no message', () => {
      fixture.componentRef.setInput('autoScroll', true);
      fixture.detectChanges();

      expect(scrollIntoView).not.toHaveBeenCalled();
    });
  });
});
