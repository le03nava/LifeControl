import { Component, input } from '@angular/core';

@Component({
  selector: 'app-page-header',
  template: `
    <header class="page-header">
      <div class="header-content">
        <h1 class="page-title">{{ title() }}</h1>
        @if (subtitle(); as sub) {
          <p class="page-subtitle">{{ sub }}</p>
        }
      </div>
      @if (showActions()) {
        <div class="header-actions">
          <ng-content />
        </div>
      }
    </header>
  `,
  styleUrl: './page-header.scss',
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  readonly showActions = input(true);
}
