import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  input,
} from '@angular/core';
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

  /**
   * Opt-in, because this banner has more than thirty consumers and most of them render it inside
   * short dialogs and lists where it is already on screen: scrolling there would be noise. A page
   * whose save action sits below a long form asks for it explicitly.
   */
  readonly autoScroll = input(false);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    effect(() => {
      if (!this.autoScroll() || this.message() === null) {
        return;
      }

      // Called optionally: a DOM without `scrollIntoView` must not turn an error banner into a
      // second error.
      this.host.nativeElement.scrollIntoView?.({ block: 'nearest' });
    });
  }
}
