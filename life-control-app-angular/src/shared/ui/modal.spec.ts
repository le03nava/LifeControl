import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Modal } from './modal';
import { Button } from './button/button';

@Component({
  standalone: true,
  imports: [Modal, Button],
  // The v22 default is OnPush; this host keeps the pre-v22 eager strategy because these
  // specs mutate plain host properties and then assert the child's rendered output.
  // Added by the official v22 `change-detection-eager` migration.
  // eslint-disable-next-line @angular-eslint/prefer-on-push-component-change-detection -- test host keeps pre-v22 eager CD
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <app-modal
      [isOpen]="isOpen"
      [title]="title"
      [size]="size"
      [showCloseButton]="showCloseButton"
      [showFooter]="showFooter"
      [closeOnBackdrop]="closeOnBackdrop"
      (modalClose)="incClose()"
    >
      <span class="projected-content">Projected body</span>
      <div slot="footer">
        <span class="footer-content">Footer buttons</span>
      </div>
    </app-modal>
  `,
})
class ModalHost {
  isOpen = true;
  title = 'Confirmar acción';
  size: 'small' | 'medium' | 'large' = 'medium';
  showCloseButton = true;
  showFooter = true;
  closeOnBackdrop = true;
  closeCount = 0;

  incClose(): void {
    this.closeCount++;
  }
}

describe('Modal', () => {
  let fixture: ComponentFixture<ModalHost>;
  let host: ModalHost;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Modal, ModalHost],
    }).compileComponents();

    fixture = TestBed.createComponent(ModalHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  function backdrop(): HTMLElement | null {
    return fixture.nativeElement.querySelector('.modal-backdrop');
  }

  function dialog(): HTMLElement | null {
    return fixture.nativeElement.querySelector('.modal-dialog');
  }

  // ---------- Open / Close state ----------
  describe('open / close', () => {
    it('should not render the dialog when isOpen is false', () => {
      host.isOpen = false;
      // Zoneless change detection only refreshes a view marked dirty; assigning a plain
      // host property does not mark it, so `detectChanges()` would skip the refresh and
      // the dev-mode check would trip NG0100.
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      expect(dialog()).toBeNull();
      expect(backdrop()).toBeNull();
    });

    it('should render the dialog when isOpen is true', () => {
      expect(backdrop()).toBeTruthy();
      expect(dialog()).toBeTruthy();
    });
  });

  // ---------- Content projection ----------
  describe('content projection', () => {
    it('should project body content', () => {
      const content = fixture.nativeElement.querySelector('.projected-content');
      expect(content).toBeTruthy();
      expect(content.textContent).toBe('Projected body');
    });

    it('should project footer slot content when showFooter is true', () => {
      const footer = fixture.nativeElement.querySelector('.footer-content');
      expect(footer).toBeTruthy();
      expect(footer.textContent).toBe('Footer buttons');
    });

    it('should not render footer slot content when showFooter is false', () => {
      host.showFooter = false;
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.modal-footer')).toBeNull();
      expect(fixture.nativeElement.querySelector('.footer-content')).toBeNull();
    });
  });

  // ---------- Title ----------
  describe('title', () => {
    it('should render the title', () => {
      const title = fixture.nativeElement.querySelector('.modal-title');
      expect(title).toBeTruthy();
      expect(title.textContent).toBe('Confirmar acción');
    });

    it('should add aria-labelledby pointing to the title when title is present', () => {
      expect(dialog()?.getAttribute('aria-labelledby')).toBe('modal-title');
    });

    it('should not render title or header when no title is provided', () => {
      host.title = '';
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.modal-header')).toBeNull();
      expect(dialog()?.getAttribute('aria-labelledby')).toBeNull();
    });
  });

  // ---------- Close button ----------
  describe('close button', () => {
    it('should emit modalClose when the close button is clicked', () => {
      const closeBtn = fixture.nativeElement.querySelector('.modal-header button[app-button]');
      expect(closeBtn).toBeTruthy();
      closeBtn.click();
      expect(host.closeCount).toBe(1);
    });

    it('should not render the close button when showCloseButton is false', () => {
      host.showCloseButton = false;
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.modal-header button[app-button]')).toBeNull();
    });
  });

  // ---------- Escape key ----------
  describe('keyboard navigation', () => {
    it('should emit modalClose when Escape is pressed on the backdrop', () => {
      backdrop()?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      expect(host.closeCount).toBe(1);
    });
  });

  // ---------- Backdrop click ----------
  describe('backdrop click', () => {
    it('should emit modalClose when the backdrop itself is clicked', () => {
      backdrop()?.click();
      expect(host.closeCount).toBe(1);
    });

    it('should NOT emit modalClose when the dialog content is clicked', () => {
      dialog()?.click();
      expect(host.closeCount).toBe(0);
    });

    it('should NOT emit modalClose on backdrop click when closeOnBackdrop is false', () => {
      host.closeOnBackdrop = false;
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      backdrop()?.click();
      expect(host.closeCount).toBe(0);
    });
  });

  // ---------- Size ----------
  describe('size', () => {
    function modalHost(): HTMLElement {
      return fixture.nativeElement.querySelector('app-modal');
    }

    it('should add the small class on the host when size is small', () => {
      host.size = 'small';
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      expect(modalHost().classList.contains('small')).toBe(true);
    });

    it('should add the large class on the host when size is large', () => {
      host.size = 'large';
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      expect(modalHost().classList.contains('large')).toBe(true);
    });

    it('should not add size classes when size is medium', () => {
      expect(modalHost().classList.contains('small')).toBe(false);
      expect(modalHost().classList.contains('large')).toBe(false);
    });
  });
});
