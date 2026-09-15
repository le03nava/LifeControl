import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Header, Footer } from '@core/layout';
import { LoadingIndicator } from '@shared/ui/loading-indicator';
import { NotificationToast } from '@shared/ui/notification-toast/notification-toast';
import { LayoutModule } from '@angular/cdk/layout';
/**
 * Main application component using modern Angular 20 architecture
 * Features:
 * - Standalone component (default in Angular 20)
 * - Signal-based reactive programming
 * - Zoneless change detection
 * - Modern template syntax with control flow
 */

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Header, Footer, LoadingIndicator, NotificationToast, LayoutModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
