import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import SalesAdminComponent from './sales-admin.component';

describe('SalesAdminComponent', () => {
  let component: SalesAdminComponent;
  let fixture: ComponentFixture<SalesAdminComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SalesAdminComponent, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(SalesAdminComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should be created successfully', () => {
    expect(component).toBeTruthy();
  });

  describe('dashboard cards', () => {
    it('should render 3 dashboard cards', () => {
      const cards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      expect(cards.length).toBe(3);
    });

    it('should have the enabled Ventas card navigate to /sales/orders', () => {
      const cards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      // First card is Ventas — enabled, no card--disabled class
      expect(cards[0].classes['card--disabled']).toBeFalsy();
      // Verify the component data drives routerLink correctly
      expect(component.cards[0].route).toBe('/sales/orders');
      expect(component.cards[0].disabled).toBe(false);
    });

    it('should not have a routerLink value on disabled cards', () => {
      const cards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      const disabledCards = component.cards.filter((c) => c.disabled);
      expect(disabledCards.length).toBe(2);
      // All disabled cards have null route → routerLink bound to null
      disabledCards.forEach((c) => expect(c.route).toBeNull());
      // Template correctly applies card--disabled class
      for (let i = 1; i < cards.length; i++) {
        expect(cards[i].classes['card--disabled']).toBe(true);
      }
    });
  });

  describe('status badges', () => {
    it('should show "Coming soon" badge on both disabled cards', () => {
      const badges = fixture.debugElement.queryAll(
        By.css('.card-badge--coming-soon'),
      );
      expect(badges.length).toBe(2);
    });

    it('should not show "Coming soon" badge on the active Ventas card', () => {
      const activeCard = fixture.debugElement.queryAll(
        By.css('.dashboard-card'),
      )[0];
      const badge = activeCard.query(By.css('.card-badge--coming-soon'));
      expect(badge).toBeNull();
    });
  });
});
