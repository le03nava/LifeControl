import { Component, input } from '@angular/core';

/**
 * Atom: Loading spinner component
 * Features:
 * - CSS-only animation for better performance
 * - Configurable size and color
 * - Accessible with proper ARIA attributes
 */
@Component({
  selector: 'app-spinner',
  template: `
    <div class="spinner" [attr.aria-label]="ariaLabel()" role="status" aria-live="polite">
      @if (showText()) {
        <span class="visually-hidden">{{ text() }}</span>
      }
    </div>
  `,
  styles: [
    `
      .spinner {
        display: inline-block;
        width: var(--spinner-size, 1.5rem);
        height: var(--spinner-size, 1.5rem);
        border: 2px solid var(--color-gray-200);
        border-top: 2px solid var(--spinner-color, var(--color-primary));
        border-radius: 50%;
        animation: spin 1s linear infinite;
      }

      :host(.small) {
        --spinner-size: 1rem;
      }

      :host(.large) {
        --spinner-size: 2rem;
      }

      :host(.white) {
        --spinner-color: white;
      }

      @keyframes spin {
        0% {
          transform: rotate(0deg);
        }
        100% {
          transform: rotate(360deg);
        }
      }
    `,
  ],
  host: {
    '[class.small]': 'size() === "small"',
    '[class.large]': 'size() === "large"',
    '[class.white]': 'color() === "white"',
  },
})
export class Spinner {
  size = input<'small' | 'medium' | 'large'>('medium');
  color = input<'primary' | 'white'>('primary');
  text = input('Loading...');
  showText = input(false);
  ariaLabel = input('Loading');
}
