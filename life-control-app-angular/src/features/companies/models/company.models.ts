import { FormControl } from '@angular/forms';

export interface Company {
  id: string;
  companyId: number;
  companyName: string;
  tipoPersonaId: number;
  razonSocial: string;
  rfc: string;
  email: string;
  phone: string;
  address?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CompanyControl {
  id: FormControl<string>;
  companyId: FormControl<number | null>;
  companyName: FormControl<string>;
  tipoPersonaId: FormControl<number>;
  razonSocial: FormControl<string>;
  rfc: FormControl<string>;
  email: FormControl<string>;
  phone: FormControl<string>;
  address: FormControl<string>;
  enabled: FormControl<boolean>;
}
