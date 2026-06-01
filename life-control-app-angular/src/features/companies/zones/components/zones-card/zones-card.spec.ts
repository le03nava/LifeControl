import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ZonesCard } from './zones-card';
import { CompanyZone } from '../../models/zone.models';

describe('ZonesCard', () => {
  let component: ZonesCard;
  let fixture: ComponentFixture<ZonesCard>;

  const activeZone: CompanyZone = {
    id: 'zone-1',
    companyRegionId: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'company-123',
    countryId: '1',
    zoneCode: 'US-CA-DT',
    zoneName: 'Downtown',
    description: 'Zona céntrica',
    displayOrder: 1,
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  const inactiveZone: CompanyZone = {
    id: 'zone-2',
    companyRegionId: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'company-123',
    countryId: '1',
    zoneCode: 'US-CA-NO',
    zoneName: 'Northside',
    description: '',
    displayOrder: undefined,
    enabled: false,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ZonesCard, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ZonesCard);
    component = fixture.componentInstance;
  });

  function setZone(zone: CompanyZone | undefined): void {
    fixture.componentRef.setInput('zone', zone);
    fixture.detectChanges();
  }

  describe('rendering', () => {
    it('should render zoneName, zoneCode, description, displayOrder, and status badge when zone is provided', () => {
      setZone(activeZone);

      const card = fixture.nativeElement.querySelector('mat-card');
      expect(card).toBeTruthy();
      expect(card.textContent).toContain('Downtown');
      expect(card.textContent).toContain('US-CA-DT');
      expect(card.textContent).toContain('Zona céntrica');
      expect(card.textContent).toContain('Ord. 1');
      expect(card.textContent).toContain('Activo');
    });

    it('should show "Activo" with active class when enabled is true', () => {
      setZone(activeZone);

      const badge = fixture.nativeElement.querySelector('.badge.status') as HTMLElement;
      expect(badge).toBeTruthy();
      expect(badge.classList.contains('active')).toBe(true);
      expect(badge.classList.contains('inactive')).toBe(false);
      expect(badge.textContent?.trim()).toBe('Activo');
    });

    it('should show "Inactivo" with inactive class when enabled is false', () => {
      setZone(inactiveZone);

      const badge = fixture.nativeElement.querySelector('.badge.status') as HTMLElement;
      expect(badge).toBeTruthy();
      expect(badge.classList.contains('inactive')).toBe(true);
      expect(badge.classList.contains('active')).toBe(false);
      expect(badge.textContent?.trim()).toBe('Inactivo');
    });

    it('should display description when present', () => {
      setZone(activeZone);

      const descEl = fixture.nativeElement.querySelector('.description');
      expect(descEl).toBeTruthy();
      expect(descEl.textContent).toContain('Zona céntrica');
    });

    it('should NOT render description paragraph when description is empty', () => {
      setZone(inactiveZone);

      const descEl = fixture.nativeElement.querySelector('.description');
      expect(descEl).toBeNull();
    });

    it('should render displayOrder badge when present', () => {
      setZone(activeZone);

      const orderBadge = fixture.nativeElement.querySelector('.badge.order-badge');
      expect(orderBadge).toBeTruthy();
      expect(orderBadge.textContent).toContain('Ord. 1');
    });

    it('should NOT render order badge when displayOrder is undefined', () => {
      setZone(inactiveZone);

      const orderBadge = fixture.nativeElement.querySelector('.badge.order-badge');
      expect(orderBadge).toBeNull();
    });

    it('should render slide-toggle for enable/disable', () => {
      setZone(activeZone);

      const toggle = fixture.nativeElement.querySelector('mat-slide-toggle');
      expect(toggle).toBeTruthy();
    });

    it('should show empty state when zone is undefined', () => {
      setZone(undefined);

      const emptyCard = fixture.nativeElement.querySelector('mat-card.card-empty');
      expect(emptyCard).toBeTruthy();
      expect(emptyCard.textContent).toContain('No hay zona disponible');
    });
  });

  describe('outputs', () => {
    it('should emit edit with zone.id on edit button click', () => {
      setZone(activeZone);

      let emittedId: string | undefined;
      component.edit.subscribe((id) => { emittedId = id; });

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar zona"]');
      expect(editBtn).toBeTruthy();
      editBtn.click();

      expect(emittedId).toBe('zone-1');
    });

    it('should emit remove with zone.id on delete button click', () => {
      setZone(activeZone);

      let emittedId: string | undefined;
      component.remove.subscribe((id) => { emittedId = id; });

      const deleteBtn = fixture.nativeElement.querySelector('button[aria-label="Eliminar zona"]');
      expect(deleteBtn).toBeTruthy();
      deleteBtn.click();

      expect(emittedId).toBe('zone-1');
    });

    it('should emit enable with correct payload on toggle', () => {
      setZone(activeZone);

      let emitted: { id: string; enable: boolean } | undefined;
      component.enable.subscribe((val) => { emitted = val; });

      component.onToggle();

      expect(emitted).toEqual({ id: 'zone-1', enable: false });
    });

    it('should emit enable with { enable: true } when toggling inactive zone', () => {
      setZone(inactiveZone);

      let emitted: { id: string; enable: boolean } | undefined;
      component.enable.subscribe((val) => { emitted = val; });

      component.onToggle();

      expect(emitted).toEqual({ id: 'zone-2', enable: true });
    });

    it('should call stopPropagation on edit button click', () => {
      setZone(activeZone);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar zona"]');
      expect(editBtn).toBeTruthy();
      editBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });

    it('should call stopPropagation on delete button click', () => {
      setZone(activeZone);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const deleteBtn = fixture.nativeElement.querySelector('button[aria-label="Eliminar zona"]');
      expect(deleteBtn).toBeTruthy();
      deleteBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });
  });

  describe('computed signals', () => {
    it('should return true for isEnabled when zone is enabled', () => {
      setZone(activeZone);
      expect(component.isEnabled()).toBe(true);
      expect(component.statusLabel()).toBe('Activo');
    });

    it('should return false for isEnabled when zone is disabled', () => {
      setZone(inactiveZone);
      expect(component.isEnabled()).toBe(false);
      expect(component.statusLabel()).toBe('Inactivo');
    });

    it('should return isEnabled as true by default when zone is undefined', () => {
      expect(component.isEnabled()).toBe(true);
      expect(component.statusLabel()).toBe('Activo');
    });
  });
});
