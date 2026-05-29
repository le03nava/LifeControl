import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CountriesCard } from './countries-card';
import { CompanyCountry } from '../../models/country.models';

describe('CountriesCard', () => {
  let component: CountriesCard;
  let fixture: ComponentFixture<CountriesCard>;

  const mockCountry: CompanyCountry = {
    id: 'cc-1',
    companyId: 'company-123',
    countryId: '1',
    countryCode: 'US',
    countryName: 'United States',
    localAlias: 'USA Office',
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  const mockCountryNoAlias: CompanyCountry = {
    id: 'cc-2',
    companyId: 'company-123',
    countryId: '2',
    countryCode: 'MX',
    countryName: 'Mexico',
    localAlias: null,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CountriesCard, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(CountriesCard);
    component = fixture.componentInstance;
  });

  function setCc(value: CompanyCountry | undefined): void {
    fixture.componentRef.setInput('cc', value);
    fixture.detectChanges();
  }

  describe('rendering', () => {
    it('should render countryName, countryCode, and localAlias when cc is provided', () => {
      setCc(mockCountry);

      const card = fixture.nativeElement.querySelector('mat-card');
      expect(card).toBeTruthy();
      expect(card.textContent).toContain('United States');
      expect(card.textContent).toContain('US');
      expect(card.textContent).toContain('USA Office');
    });

    it('should render countryCode badge', () => {
      setCc(mockCountry);

      const badge = fixture.nativeElement.querySelector('.badge.country-code-badge');
      expect(badge).toBeTruthy();
      expect(badge.textContent).toContain('US');
    });

    it('should show empty state when cc is undefined', () => {
      setCc(undefined);

      const emptyCard = fixture.nativeElement.querySelector('mat-card.card-empty');
      expect(emptyCard).toBeTruthy();
      expect(emptyCard.textContent).toContain('No hay país disponible');
    });

    it('should not show localAlias when it is null', () => {
      setCc(mockCountryNoAlias);

      const alias = fixture.nativeElement.querySelector('.local-alias');
      expect(alias).toBeNull();
    });
  });

  describe('outputs', () => {
    it('should emit editCountry with cc.id on edit button click', () => {
      setCc(mockCountry);

      let emittedId: string | undefined;
      component.editCountry.subscribe((id) => { emittedId = id; });

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar país"]');
      expect(editBtn).toBeTruthy();
      editBtn.click();

      expect(emittedId).toBe('cc-1');
    });

    it('should emit deleteCountry with cc.id on delete button click', () => {
      setCc(mockCountry);

      let emittedId: string | undefined;
      component.deleteCountry.subscribe((id) => { emittedId = id; });

      const deleteBtn = fixture.nativeElement.querySelector('button[aria-label="Eliminar país"]');
      expect(deleteBtn).toBeTruthy();
      deleteBtn.click();

      expect(emittedId).toBe('cc-1');
    });

    it('should call stopPropagation on edit button click', () => {
      setCc(mockCountry);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar país"]');
      expect(editBtn).toBeTruthy();
      editBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });

    it('should call stopPropagation on delete button click', () => {
      setCc(mockCountry);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const deleteBtn = fixture.nativeElement.querySelector('button[aria-label="Eliminar país"]');
      expect(deleteBtn).toBeTruthy();
      deleteBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });
  });
});
