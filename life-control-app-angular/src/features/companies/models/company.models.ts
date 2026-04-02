import { FormControl } from '@angular/forms';

export interface Company {
  id: string;
  companyKey: string;
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
  companyKey: FormControl<string>;
  companyName: FormControl<string>;
  tipoPersonaId: FormControl<number>;
  razonSocial: FormControl<string>;
  rfc: FormControl<string>;
  email: FormControl<string>;
  phone: FormControl<string>;
  address: FormControl<string>;
  enabled: FormControl<boolean>;
}
