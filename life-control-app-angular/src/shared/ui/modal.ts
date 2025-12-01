import { Component, input, output } from '@angular/core';
import { Button } from './button/button';

/**
 * Molecule: Modal dialog component
 * Features:
 * - Signal-based open/close state
 * - Keyboard navigation (Escape to close)
 * - Focus management for accessibility
 * - Backdrop click to close
 */
@Component({
  selector: 'app-modal',
  imports: [Button],
  template: `
    @if (isOpen()) {
      <div class="modal-backdrop" (click)="handleBackdropClick($event)" (keydown.escape)="close()">
        <div
          class="modal-dialog"
          role="dialog"
          [attr.aria-labelledby]="title() ? 'modal-title' : null"
          [attr.aria-describedby]="'modal-content'"
          aria-modal="true"
          tabindex="-1"
        >
          @if (title()) {
            <div class="modal-header">
              <h2 id="modal-title" class="modal-title">{{ title() }}</h2>
              @if (showCloseButton()) {
                <button
                  app-button
                  variant="secondary"
                  size="small"
                  ariaLabel="Close modal"
                  (buttonClick)="close()"
                >
                  ✕
                </button>
              }
            </div>
          }

          <div id="modal-content" class="modal-content">
            <ng-content />
          </div>

          @if (showFooter()) {
            <div class="modal-footer">
              <ng-content select="[slot=footer]" />
            </div>
          }
        </div>
      </div>
    }
  `,
  styles: [
    `
      .modal-backdrop {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background-color: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
        padding: var(--space-md);
      }

      .modal-dialog {
        background: white;
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        max-width: var(--modal-width, 32rem);
        width: 100%;
        max-height: 90vh;
        overflow: hidden;
        animation: modalEnter 0.2s ease-out;
      }

      .modal-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--space-lg);
        border-bottom: 1px solid var(--color-gray-200);
      }

      .modal-title {
        margin: 0;
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--color-gray-900);
      }

      .modal-content {
        padding: var(--space-lg);
        overflow-y: auto;
      }

      .modal-footer {
        padding: var(--space-lg);
        border-top: 1px solid var(--color-gray-200);
        display: flex;
        gap: var(--space-sm);
        justify-content: flex-end;
      }

      @keyframes modalEnter {
        from {
          opacity: 0;
          transform: scale(0.95);
        }
        to {
          opacity: 1;
          transform: scale(1);
        }
      }

      :host(.small) .modal-dialog {
        --modal-width: 20rem;
      }

      :host(.large) .modal-dialog {
        --modal-width: 48rem;
      }
    `,
  ],
  host: {
    '[class.small]': 'size() === "small"',
    '[class.large]': 'size() === "large"',
  },
})
export class Modal {
  isOpen = input.required<boolean>();
  title = input<string>();
  size = input<'small' | 'medium' | 'large'>('medium');
  showCloseButton = input(true);
  showFooter = input(true);
  closeOnBackdrop = input(true);

  modalClose = output<void>();

  handleBackdropClick(event: Event) {
    if (this.closeOnBackdrop() && event.target === event.currentTarget) {
      this.close();
    }
  }

  close() {
    this.modalClose.emit();
  }
}
