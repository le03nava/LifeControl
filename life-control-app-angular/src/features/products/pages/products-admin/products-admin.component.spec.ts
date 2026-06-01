import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ProductsAdminComponent } from './products-admin.component';

describe('ProductsAdminComponent', () => {
  let component: ProductsAdminComponent;
  let fixture: ComponentFixture<ProductsAdminComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductsAdminComponent, MatCardModule, MatIconModule, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductsAdminComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render dashboard title "Products Administration"', () => {
    const title = fixture.nativeElement.querySelector('.dashboard-title');
    expect(title).toBeTruthy();
    expect(title.textContent.trim()).toBe('Products Administration');
  });

  it('should render 4 dashboard cards', () => {
    const cards = fixture.nativeElement.querySelectorAll('.dashboard-card');
    expect(cards).toHaveLength(4);
  });

  it('should have routerLink on the All Products card pointing to /products/list', () => {
    const allProductsCard = fixture.debugElement.queryAll(By.css('.dashboard-card'))[0];
    expect(allProductsCard).toBeTruthy();
    expect(allProductsCard.classes['card--disabled']).toBeFalsy();
    expect(component.cards[0].route).toBe('/products/list');
    expect(allProductsCard.injector.get(RouterLink)).toBeTruthy();
  });

  it('should have RouterLink directive on every active card', () => {
    const activeCards = fixture.debugElement.queryAll(
      By.css('.dashboard-card:not(.card--disabled)'),
    );
    expect(activeCards).toHaveLength(2);
    for (const card of activeCards) {
      expect(card.injector.get(RouterLink)).toBeTruthy();
    }
  });

  it('should have RouterLink on every disabled card (with null value)', () => {
    const disabledCards = fixture.debugElement.queryAll(
      By.css('.dashboard-card.card--disabled'),
    );
    expect(disabledCards).toHaveLength(2);
    for (const card of disabledCards) {
      expect(card.injector.get(RouterLink)).toBeTruthy();
    }
  });

  it('should display "Coming soon" on each disabled card', () => {
    const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
    expect(comingSoonBadges).toHaveLength(2);
    comingSoonBadges.forEach((badge: Element) => {
      expect(badge.textContent.trim()).toContain('Coming soon');
    });
  });

  it('should render each placeholder card with correct title from data', () => {
    const cardTitles: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-title');
    const titles = Array.from(cardTitles).map((el: Element) => el.textContent?.trim() ?? '');

    expect(titles).toContain('All Products');
    expect(titles).toContain('New Product');
    expect(titles).toContain('Categories');
    expect(titles).toContain('Product Types');
  });

  it('should render the dashboard subtitle', () => {
    const subtitle = fixture.nativeElement.querySelector('.dashboard-subtitle');
    expect(subtitle).toBeTruthy();
    expect(subtitle.textContent.trim()).toBe(
      'Manage products, categories, and product types',
    );
  });

  it('should render active cards with "Manage" call-to-action text', () => {
    const actionElements = fixture.nativeElement.querySelectorAll('.card-action');
    expect(actionElements).toHaveLength(2);
    expect(actionElements[0].textContent.trim()).toContain('Manage All Products');
    expect(actionElements[1].textContent.trim()).toContain('Manage New Product');
  });

  it('should render card icons with correct Material icon names', () => {
    const icons: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-icon mat-icon');
    expect(icons).toHaveLength(4);

    const iconNames = Array.from(icons).map((el: Element) =>
      el.getAttribute('data-mat-icon-name'),
    );
    expect(iconNames).toContain('inventory_2');
    expect(iconNames).toContain('add');
    expect(iconNames).toContain('category');
    expect(iconNames).toContain('label');
  });

  it('should render 2 "Coming soon" badges (one per disabled card)', () => {
    const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
    expect(comingSoonBadges).toHaveLength(2);
  });
});
