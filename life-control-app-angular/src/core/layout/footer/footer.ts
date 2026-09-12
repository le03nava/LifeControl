import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'footer[app-footer]',
  imports: [],
  templateUrl: `footer.html`,
  styleUrl: `footer.scss`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Footer {
  currentYear = new Date().getFullYear();
}