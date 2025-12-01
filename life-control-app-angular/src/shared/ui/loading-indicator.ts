import { Component, inject } from '@angular/core';
import { LoadingService } from '@shared/data/loading';
import { Spinner } from './spinner';

/**
 * Global loading indicator component
 * Shows when any loading state is active
 */
@Component({
  selector: 'app-loading-indicator',
  imports: [Spinner],
  template: `
    @if (loadingService.isLoading()) {
      <div class="loading-overlay">
        <div class="loading-content">
          <app-spinner size="large" />
          <span class="loading-text">Loading...</span>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .loading-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background-color: rgba(255, 255, 255, 0.8);
        backdrop-filter: blur(2px);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 9999;
      }

      .loading-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-md);
      }

      .loading-text {
        color: var(--color-gray-600);
        font-weight: 500;
      }
    `,
  ],
})
export class LoadingIndicator {
  loadingService = inject(LoadingService);
}
