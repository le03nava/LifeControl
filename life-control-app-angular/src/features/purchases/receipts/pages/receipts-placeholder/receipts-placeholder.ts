import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';

/**
 * Simple placeholder page for the Receipts module.
 * Displays a "Coming Soon" message with an icon.
 *
 * Covers spec Requirement 2, scenario 2.2.
 */
@Component({
  selector: 'app-receipts-placeholder',
  standalone: true,
  imports: [MatIconModule, MatCardModule],
  template: `
    <div class="receipts-placeholder">
      <mat-card appearance="outlined" class="placeholder-card">
        <mat-card-content>
          <div class="placeholder-content">
            <mat-icon fontIcon="receipt_long" class="placeholder-icon" />
            <h2>Receipts</h2>
            <p>Coming Soon</p>
            <p class="placeholder-subtitle">
              La gestión de recibos estará disponible próximamente.
            </p>
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [
    `
      :host {
        display: flex;
        justify-content: center;
        align-items: center;
        min-height: 60vh;
        padding: var(--space-lg);
      }

      .placeholder-card {
        max-width: 480px;
        width: 100%;
        text-align: center;
      }

      .placeholder-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-md);
        padding: var(--space-xl) 0;
      }

      .placeholder-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--mat-sys-outline, #9e9e9e);
      }

      .placeholder-subtitle {
        color: var(--mat-sys-outline, #757575);
        font-size: 0.875rem;
      }

      h2 {
        margin: 0;
        font-size: 1.5rem;
      }

      p {
        margin: 0;
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceiptsPlaceholder {}
