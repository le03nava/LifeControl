import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-error-banner',
  standalone: true,
  imports: [MatIconModule],
  templateUrl: './error-banner.html',
  styleUrl: './error-banner.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ErrorBanner {
  readonly message = input<string | null>(null);
}
