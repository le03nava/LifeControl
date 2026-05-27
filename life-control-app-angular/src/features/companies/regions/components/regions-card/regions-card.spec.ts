import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RegionsCard } from './regions-card';
import { CompanyRegion } from '../../models/region.models';

describe('RegionsCard', () => {
  let component: RegionsCard;
  let fixture: ComponentFixture<RegionsCard>;

  const activeRegion: CompanyRegion = {
    id: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'company-123',
    countryId: '1',
    regionCode: 'US-CA',
    regionName: 'California',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  const inactiveRegion: CompanyRegion = {
    id: 'reg-2',
    companyCountryId: 'cc-1',
    companyId: 'company-123',
    countryId: '1',
    regionCode: 'US-TX',
    regionName: 'Texas',
    enabled: false,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegionsCard, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(RegionsCard);
    component = fixture.componentInstance;
  });

  function setRegion(region: CompanyRegion | undefined): void {
    fixture.componentRef.setInput('region', region);
    fixture.detectChanges();
  }

  describe('rendering', () => {
    it('should render regionName, regionCode, and status badge when region is provided', () => {
      setRegion(activeRegion);

      const card = fixture.nativeElement.querySelector('mat-card');
      expect(card).toBeTruthy();
      expect(card.textContent).toContain('California');
      expect(card.textContent).toContain('US-CA');
      expect(card.textContent).toContain('Activo');
    });

    it('should show "Activo" with active class when enabled is true', () => {
      setRegion(activeRegion);

      const badge = fixture.nativeElement.querySelector('.badge.status') as HTMLElement;
      expect(badge).toBeTruthy();
      expect(badge.classList.contains('active')).toBe(true);
      expect(badge.classList.contains('inactive')).toBe(false);
      expect(badge.textContent?.trim()).toBe('Activo');
    });

    it('should show "Inactivo" with inactive class when enabled is false', () => {
      setRegion(inactiveRegion);

      const badge = fixture.nativeElement.querySelector('.badge.status') as HTMLElement;
      expect(badge).toBeTruthy();
      expect(badge.classList.contains('inactive')).toBe(true);
      expect(badge.classList.contains('active')).toBe(false);
      expect(badge.textContent?.trim()).toBe('Inactivo');
    });

    it('should show empty state when region is undefined', () => {
      setRegion(undefined);

      const emptyCard = fixture.nativeElement.querySelector('mat-card.card-empty');
      expect(emptyCard).toBeTruthy();
      expect(emptyCard.textContent).toContain('No hay región disponible');
    });
  });

  describe('outputs', () => {
    it('should emit editRegion with region.id on edit button click', () => {
      setRegion(activeRegion);

      let emittedId: string | undefined;
      component.editRegion.subscribe((id) => { emittedId = id; });

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar región"]');
      expect(editBtn).toBeTruthy();
      editBtn.click();

      expect(emittedId).toBe('reg-1');
    });

    it('should emit deleteRegion with region.id on delete button click', () => {
      setRegion(activeRegion);

      let emittedId: string | undefined;
      component.deleteRegion.subscribe((id) => { emittedId = id; });

      const deleteBtn = fixture.nativeElement.querySelector('button[aria-label="Eliminar región"]');
      expect(deleteBtn).toBeTruthy();
      deleteBtn.click();

      expect(emittedId).toBe('reg-1');
    });

    it('should call stopPropagation on edit button click', () => {
      setRegion(activeRegion);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar región"]');
      expect(editBtn).toBeTruthy();
      editBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });

    it('should call stopPropagation on delete button click', () => {
      setRegion(activeRegion);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const deleteBtn = fixture.nativeElement.querySelector('button[aria-label="Eliminar región"]');
      expect(deleteBtn).toBeTruthy();
      deleteBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });
  });

  describe('computed signals', () => {
    it('should return true for isEnabled when region is enabled', () => {
      setRegion(activeRegion);
      expect(component.isEnabled()).toBe(true);
      expect(component.statusLabel()).toBe('Activo');
    });

    it('should return false for isEnabled when region is disabled', () => {
      setRegion(inactiveRegion);
      expect(component.isEnabled()).toBe(false);
      expect(component.statusLabel()).toBe('Inactivo');
    });

    it('should return isEnabled as true by default when region is undefined', () => {
      // When no region is set, the input is undefined and the default enabled is true
      expect(component.isEnabled()).toBe(true);
      expect(component.statusLabel()).toBe('Activo');
    });
  });
});
