import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Button } from './button';

@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Button],
  template: `<button app-button>
    <span class="btn-label">Save</span>
  </button>`,
})
class ButtonHost {}

describe('Button', () => {
  let fixture: ComponentFixture<Button>;
  let component: Button;
  let hostFixture: ComponentFixture<ButtonHost>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Button, ButtonHost],
    }).compileComponents();
  });

  function createButton(): void {
    fixture = TestBed.createComponent(Button);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  // ---------- Rendering & projections ----------
  describe('rendering', () => {
    it('should be created', () => {
      createButton();
      expect(component).toBeTruthy();
    });

    it('should project content into the button', () => {
      hostFixture = TestBed.createComponent(ButtonHost);
      hostFixture.detectChanges();

      const label = hostFixture.nativeElement.querySelector('.btn-label');
      expect(label).toBeTruthy();
      expect(label.textContent).toBe('Save');
    });

    it('should set the type attribute to button by default', () => {
      createButton();
      const btn = fixture.nativeElement as HTMLButtonElement;
      expect(btn.getAttribute('type')).toBe('button');
    });

    it('should reflect the type input on the attribute', () => {
      createButton();
      fixture.componentRef.setInput('type', 'submit');
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLButtonElement).getAttribute('type')).toBe('submit');
    });

    it('should set aria-label when provided', () => {
      createButton();
      fixture.componentRef.setInput('ariaLabel', 'Close modal');
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLButtonElement).getAttribute('aria-label')).toBe(
        'Close modal',
      );
    });
  });

  // ---------- Variants ----------
  describe('variant', () => {
    it('should add primary class when variant is primary', () => {
      createButton();
      fixture.componentRef.setInput('variant', 'primary');
      fixture.detectChanges();
      expect(fixture.nativeElement.classList).toContain('primary');
    });

    it('should add secondary class when variant is secondary', () => {
      createButton();
      fixture.componentRef.setInput('variant', 'secondary');
      fixture.detectChanges();
      expect(fixture.nativeElement.classList).toContain('secondary');
    });

    it('should add danger class when variant is danger', () => {
      createButton();
      fixture.componentRef.setInput('variant', 'danger');
      fixture.detectChanges();
      expect(fixture.nativeElement.classList).toContain('danger');
    });
  });

  // ---------- Sizes ----------
  describe('size', () => {
    it('should add small class when size is small', () => {
      createButton();
      fixture.componentRef.setInput('size', 'small');
      fixture.detectChanges();
      expect(fixture.nativeElement.classList).toContain('small');
    });

    it('should add large class when size is large', () => {
      createButton();
      fixture.componentRef.setInput('size', 'large');
      fixture.detectChanges();
      expect(fixture.nativeElement.classList).toContain('large');
    });

    it('should not add size classes when size is none', () => {
      createButton();
      expect(fixture.nativeElement.classList.contains('small')).toBe(false);
      expect(fixture.nativeElement.classList.contains('large')).toBe(false);
    });
  });

  // ---------- Disabled state ----------
  describe('disabled', () => {
    it('should set the disabled attribute on the button', () => {
      createButton();
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLButtonElement).disabled).toBe(true);
    });

    it('should not emit buttonClick when disabled and clicked', () => {
      createButton();
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();

      let emitted = false;
      component.buttonClick.subscribe(() => {
        emitted = true;
      });
      component.handleClick(new Event('click'));

      expect(emitted).toBe(false);
    });

    it('should prevent default on the click event when disabled', () => {
      createButton();
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();

      const event = new Event('click', { cancelable: true });
      component.handleClick(event);

      expect(event.defaultPrevented).toBe(true);
    });
  });

  // ---------- Click / Output ----------
  describe('buttonClick', () => {
    it('should emit buttonClick when clicked while enabled', () => {
      createButton();

      let emitted = false;
      component.buttonClick.subscribe(() => {
        emitted = true;
      });

      (fixture.nativeElement as HTMLButtonElement).click();

      expect(emitted).toBe(true);
    });

    it('should emit the underlying click event', () => {
      createButton();

      let emittedEvent: unknown;
      component.buttonClick.subscribe((event: Event) => {
        emittedEvent = event;
      });

      (fixture.nativeElement as HTMLButtonElement).click();

      expect(emittedEvent).toBeInstanceOf(Event);
    });

    it('should not emit buttonClick when the button is disabled via DOM interaction', () => {
      createButton();
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();

      let emitted = false;
      component.buttonClick.subscribe(() => {
        emitted = true;
      });

      (fixture.nativeElement as HTMLButtonElement).click();

      expect(emitted).toBe(false);
    });
  });
});
