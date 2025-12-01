import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
/**
 * Atom: Input component with signal-based API and form integration
 * Features:
 * - Implements ControlValueAccessor for reactive forms
 * - Signal-based inputs for modern reactivity
 * - Accessible with proper ARIA attributes
 * - Type-safe validation states
 */
@Component({
  selector: 'a[app-hyperlink]',
  templateUrl: `./hyperlink.html`,
  styleUrl: `./hyperlink.scss`,
  imports: [CommonModule, RouterModule],
  host: {
    '[class.primary]': 'variant() === "primary"',
    '[class.secondary]': 'variant() === "secondary"',
    '[class.small]': 'size() === "small"',
    '[class.large]': 'size() === "large"',
  },
})
export class Hyperlink {
  destinationUrl = input<string>('');
  variant = input<'primary' | 'secondary' | 'none'>('none');
  size = input<'small' | 'medium' | 'large' | 'none'>('none');
}
