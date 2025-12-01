import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from '@shared/ui/button/button';

/**
 * 404 Not Found component
 * Simple error page with navigation options
 */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink, Button],
  template: `
    <div class="not-found-page">
      <div class="not-found-content">
        <div class="error-code">404</div>
        <h1 class="error-title">Page Not Found</h1>
        <p class="error-description">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <div class="error-actions">
          <button variant="primary" routerLink="/dashboard">app-button Go to Dashboard</button>
          <button app-button variant="secondary" (buttonClick)="goBack()">Go Back</button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .not-found-page {
        min-height: 60vh;
        display: flex;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: var(--space-2xl);
      }

      .not-found-content {
        max-width: 500px;
      }

      .error-code {
        font-size: 8rem;
        font-weight: 900;
        color: var(--color-gray-300);
        line-height: 1;
        margin-bottom: var(--space-lg);
      }

      .error-title {
        margin: 0 0 var(--space-md);
        font-size: 2rem;
        font-weight: 700;
        color: var(--color-gray-900);
      }

      .error-description {
        margin: 0 0 var(--space-xl);
        font-size: 1.125rem;
        color: var(--color-gray-600);
        line-height: 1.6;
      }

      .error-actions {
        display: flex;
        gap: var(--space-md);
        justify-content: center;
        flex-wrap: wrap;
      }
    `,
  ],
})
export class NotFound {
  goBack(): void {
    history.back();
  }
}
