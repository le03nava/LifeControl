import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Company } from '../../models/company.models';

@Component({
  selector: 'app-companies-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  templateUrl: './companies-table.html',
  styleUrl: './companies-table.scss',
})
export class CompaniesTable {
  companies = input<Company[]>([]);
}
