import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { StoresCard } from './stores-card';
import { CompanyStore } from '../../models/store.models';

describe('StoresCard', () => {
  let component: StoresCard;
  let fixture: ComponentFixture<StoresCard>;

  const activeStore: CompanyStore = {
    id: 'store-1',
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Central',
    email: 'central@store.com',
    phoneNumber: '+525512345678',
    street: 'Av. Reforma',
    streetNumber: '222',
    internalNumber: 'A-101',
    neighborhood: 'Juárez',
    zipCode: '06600',
    city: 'Ciudad de México',
    state: 'CDMX',
    countryId: 'MX',
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  const inactiveStore: CompanyStore = {
    id: 'store-2',
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Inactiva',
    email: '',
    phoneNumber: '',
    enabled: false,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StoresCard, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(StoresCard);
    component = fixture.componentInstance;
  });

  function setStore(store: CompanyStore | undefined): void {
    fixture.componentRef.setInput('store', store);
    fixture.detectChanges();
  }

  describe('rendering', () => {
    it('should render storeName, email, phone, and address summary when store is provided', () => {
      setStore(activeStore);

      const card = fixture.nativeElement.querySelector('mat-card');
      expect(card).toBeTruthy();
      expect(card.textContent).toContain('Tienda Central');
      expect(card.textContent).toContain('central@store.com');
      expect(card.textContent).toContain('+525512345678');
      expect(card.textContent).toContain('Av. Reforma 222');
      expect(card.textContent).toContain('Ciudad de México, CDMX');
    });

    it('should show "Activo" with active class when enabled is true', () => {
      setStore(activeStore);

      const badge = fixture.nativeElement.querySelector('.badge.status') as HTMLElement;
      expect(badge).toBeTruthy();
      expect(badge.classList.contains('active')).toBe(true);
      expect(badge.classList.contains('inactive')).toBe(false);
      expect(badge.textContent?.trim()).toBe('Activo');
    });

    it('should show "Inactivo" with inactive class when enabled is false', () => {
      setStore(inactiveStore);

      const badge = fixture.nativeElement.querySelector('.badge.status') as HTMLElement;
      expect(badge).toBeTruthy();
      expect(badge.classList.contains('inactive')).toBe(true);
      expect(badge.classList.contains('active')).toBe(false);
      expect(badge.textContent?.trim()).toBe('Inactivo');
    });

    it('should render address section with street, streetNumber, city, and state', () => {
      setStore(activeStore);

      const addressEl = fixture.nativeElement.querySelector('.address-summary');
      expect(addressEl).toBeTruthy();
      expect(addressEl.textContent).toContain('Av. Reforma 222');
    });

    it('should render email and phone when present', () => {
      setStore(activeStore);

      const cardText = fixture.nativeElement.querySelector('mat-card').textContent;
      expect(cardText).toContain('central@store.com');
      expect(cardText).toContain('+525512345678');
    });

    it('should render slide-toggle for enable/disable', () => {
      setStore(activeStore);

      const toggle = fixture.nativeElement.querySelector('mat-slide-toggle');
      expect(toggle).toBeTruthy();
    });

    it('should show empty state when store is undefined', () => {
      setStore(undefined);

      const emptyCard = fixture.nativeElement.querySelector('mat-card.card-empty');
      expect(emptyCard).toBeTruthy();
      expect(emptyCard.textContent).toContain('No hay tienda disponible');
    });

    it('should render address fields when some are missing', () => {
      const partialStore: CompanyStore = {
        ...activeStore,
        street: 'Calle Principal',
        streetNumber: '',
        city: 'Monterrey',
        state: 'NL',
      };
      setStore(partialStore);

      const address = fixture.nativeElement.querySelector('.address-summary');
      expect(address).toBeTruthy();
      expect(address.textContent).toContain('Calle Principal');
      expect(address.textContent).toContain('Monterrey, NL');
    });
  });

  describe('outputs', () => {
    it('should emit edit with store.id on edit button click', () => {
      setStore(activeStore);

      let emittedId: string | undefined;
      component.edit.subscribe((id) => { emittedId = id; });

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar tienda"]');
      expect(editBtn).toBeTruthy();
      editBtn.click();

      expect(emittedId).toBe('store-1');
    });

    it('should emit toggle with store.id on toggle change', () => {
      setStore(activeStore);

      let emittedId: string | undefined;
      component.toggle.subscribe((id) => { emittedId = id; });

      component.onToggle();

      expect(emittedId).toBe('store-1');
    });

    it('should call stopPropagation on edit button click', () => {
      setStore(activeStore);

      const clickEvent = new MouseEvent('click', { bubbles: true });
      const stopSpy = vi.spyOn(clickEvent, 'stopPropagation');

      const editBtn = fixture.nativeElement.querySelector('button[aria-label="Editar tienda"]');
      expect(editBtn).toBeTruthy();
      editBtn.dispatchEvent(clickEvent);

      expect(stopSpy).toHaveBeenCalled();
    });
  });

  describe('computed signals', () => {
    it('should return true for isEnabled when store is enabled', () => {
      setStore(activeStore);
      expect(component.isEnabled()).toBe(true);
      expect(component.statusLabel()).toBe('Activo');
    });

    it('should return false for isEnabled when store is disabled', () => {
      setStore(inactiveStore);
      expect(component.isEnabled()).toBe(false);
      expect(component.statusLabel()).toBe('Inactivo');
    });

    it('should return isEnabled as true by default when store is undefined', () => {
      expect(component.isEnabled()).toBe(true);
      expect(component.statusLabel()).toBe('Activo');
    });

    it('should compute addressSummary with street and streetNumber', () => {
      setStore(activeStore);
      expect(component.addressSummary()).toContain('Av. Reforma 222');
    });

    it('should compute addressSummary with city and state', () => {
      setStore(activeStore);
      expect(component.addressSummary()).toContain('Ciudad de México, CDMX');
    });

    it('should compute addressSummary showing only available fields', () => {
      const minimalStore: CompanyStore = {
        ...activeStore,
        street: 'Calle 1',
        streetNumber: undefined,
        city: undefined,
        state: undefined,
      };
      setStore(minimalStore);
      expect(component.addressSummary()).toBe('Calle 1');
    });
  });
});
