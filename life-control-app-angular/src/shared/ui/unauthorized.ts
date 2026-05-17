import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from '@shared/ui/button/button';

/**
 * Unauthorized access component
 * Displayed when a user lacks the required role to access a route
 */
@Component({
  selector: 'app-unauthorized',
  imports: [RouterLink, Button],
  template: `
    <div class="unauthorized-page">
      <div class="unauthorized-content">
        <div class="error-code">403</div>
        <h1 class="error-title">Access Denied</h1>
        <p class="error-description">
          You don't have permission to access this page. Please contact your
          administrator if you believe this is an error.
        </p>
        <div class="error-actions">
          <button app-button variant="primary" routerLink="/">Go to Home</button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .unauthorized-page {
        min-height: 60vh;
        display: flex;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: var(--space-2xl);
      }

      .unauthorized-content {
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
export class Unauthorized {}
