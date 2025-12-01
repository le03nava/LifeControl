import { Component, input, output } from '@angular/core';

/**
 * Atom: Basic button component following modern Angular 20 patterns
 * Features:
 * - Signal-based inputs and outputs
 * - Host object for better performance
 * - Accessible by default
 * - Type-safe with strict TypeScript
 */
@Component({
  selector: 'button[app-button]',
  templateUrl: `./button.html`,
  styleUrl: `./button.scss`,
  host: {
    '[attr.type]': 'type()',
    '[class.primary]': 'variant() === "primary"',
    '[class.secondary]': 'variant() === "secondary"',
    '[class.danger]': 'variant() === "danger"',
    '[class.small]': 'size() === "small"',
    '[class.large]': 'size() === "large"',
    '[disabled]': 'disabled()',
    '[attr.aria-label]': 'ariaLabel()',
    '(click)': 'handleClick($event)',
  },
})
export class Button {
  variant = input<'primary' | 'secondary' | 'danger' | 'none'>('none');
  size = input<'small' | 'medium' | 'large' | 'none'>('none');
  disabled = input(false);
  ariaLabel = input<string>();
  type = input<'button' | 'submit' | 'reset'>('button');

  buttonClick = output<Event>();

  handleClick(event: Event) {
    if (this.disabled()) {
      event.preventDefault();
      return;
    }
    this.buttonClick.emit(event);
  }
}
