import { signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CompanySelector } from './company-selector';
import { CompanyContextService } from '../../data/company-context.service';
import { Company } from '@features/companies/companies/models/company.models';

function createCompany(id: string, key: string, name: string): Company {
  return {
    id,
    companyKey: key,
    companyName: name,
    tipoPersonaId: 1,
    razonSocial: name,
    rfc: `RFC${id}`,
    email: `contact@${key}.com`,
    phone: '5551234567',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };
}

describe('CompanySelector', () => {
  const mockAcme: Company = createCompany('1', 'acme', 'Acme Corp');
  const mockGlobex: Company = createCompany('2', 'globex', 'Globex Inc');

  let fixture: ComponentFixture<CompanySelector>;
  let component: CompanySelector;
  let setCurrentCompany: ReturnType<typeof vi.fn>;
  let companiesSignal: WritableSignal<Company[]>;
  let loadingSignal: WritableSignal<boolean>;

  beforeEach(async () => {
    companiesSignal = signal<Company[]>([mockAcme, mockGlobex]);
    loadingSignal = signal(false);
    const currentCompany = signal<Company | null>(null);
    setCurrentCompany = vi.fn();

    await TestBed.configureTestingModule({
      imports: [CompanySelector, NoopAnimationsModule],
      providers: [
        {
          provide: CompanyContextService,
          useValue: {
            companies: companiesSignal.asReadonly(),
            loading: loadingSignal.asReadonly(),
            currentCompany: currentCompany.asReadonly(),
            setCurrentCompany,
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CompanySelector);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ---------- Rendering states ----------
  describe('rendering', () => {
    it('should render a mat-select when companies exist and not loading', () => {
      expect(fixture.nativeElement.querySelector('mat-select')).toBeTruthy();
    });

    it('should show "Cargando..." while loading', () => {
      loadingSignal.set(true);
      fixture.detectChanges();

      const loadingText = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingText).toBeTruthy();
      expect(loadingText.textContent).toContain('Cargando...');
    });

    it('should show "Sin empresas" when there are no companies and not loading', () => {
      companiesSignal.set([]);
      fixture.detectChanges();

      const emptyLabel = fixture.nativeElement.querySelector('.empty-label');
      expect(emptyLabel).toBeTruthy();
      expect(emptyLabel.textContent).toContain('Sin empresas');
    });
  });

  // ---------- Selection ----------
  describe('onCompanyChange', () => {
    it('should call setCurrentCompany with the selected company', () => {
      component.onCompanyChange('globex');
      expect(setCurrentCompany).toHaveBeenCalledWith(mockGlobex);
    });

    it('should call setCurrentCompany when the key matches the first company', () => {
      component.onCompanyChange('acme');
      expect(setCurrentCompany).toHaveBeenCalledWith(mockAcme);
    });

    it('should NOT call setCurrentCompany for an unknown key', () => {
      component.onCompanyChange('unknown');
      expect(setCurrentCompany).not.toHaveBeenCalled();
    });
  });

  // ---------- Current company ----------
  describe('current company', () => {
    it('should expose the current company signal from the context service', () => {
      expect(component.currentCompany()).toBeNull();
    });
  });
});
