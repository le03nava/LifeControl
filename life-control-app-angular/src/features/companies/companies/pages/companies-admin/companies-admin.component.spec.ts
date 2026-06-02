import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { CompaniesAdminComponent } from './companies-admin.component';

describe('CompaniesAdminComponent', () => {
  let component: CompaniesAdminComponent;
  let fixture: ComponentFixture<CompaniesAdminComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompaniesAdminComponent, MatCardModule, MatIconModule, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(CompaniesAdminComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render dashboard title "Companies Administration"', () => {
    const title = fixture.nativeElement.querySelector('.dashboard-title');
    expect(title).toBeTruthy();
    expect(title.textContent.trim()).toBe('Companies Administration');
  });

  it('should render 5 dashboard cards', () => {
    const cards = fixture.nativeElement.querySelectorAll('.dashboard-card');
    expect(cards).toHaveLength(5);
  });

  it('should have routerLink on the Countries card pointing to /companies/countries', () => {
    const countryCard = fixture.debugElement.queryAll(By.css('.dashboard-card'))[1];
    expect(countryCard).toBeTruthy();
    expect(countryCard.classes['card--disabled']).toBeFalsy();
    expect(component.cards[1].route).toBe('/companies/countries');
    expect(countryCard.injector.get(RouterLink)).toBeTruthy();
  });

  it('should have RouterLink directive on every active card', () => {
    const activeCards = fixture.debugElement.queryAll(
      By.css('.dashboard-card:not(.card--disabled)'),
    );
    expect(activeCards).toHaveLength(5);
    for (const card of activeCards) {
      expect(card.injector.get(RouterLink)).toBeTruthy();
    }
  });

  it('should have no disabled cards when all are active', () => {
    const disabledCards = fixture.debugElement.queryAll(
      By.css('.dashboard-card.card--disabled'),
    );
    expect(disabledCards).toHaveLength(0);
  });

  it('should display no "Coming soon" badges when all cards are active', () => {
    const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
    expect(comingSoonBadges).toHaveLength(0);
  });

  it('should render each placeholder card with correct title from data', () => {
    const cardTitles: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-title');
    const titles = Array.from(cardTitles).map((el: Element) => el.textContent?.trim() ?? '');

    expect(titles).toContain('Companies');
    expect(titles).toContain('Countries');
    expect(titles).toContain('Regions');
    expect(titles).toContain('Zones');
    expect(titles).toContain('Stores');
  });

  it('should render the dashboard subtitle', () => {
    const subtitle = fixture.nativeElement.querySelector('.dashboard-subtitle');
    expect(subtitle).toBeTruthy();
    expect(subtitle.textContent.trim()).toBe(
      'Manage companies, countries, regions, zones, and stores',
    );
  });

  it('should render active cards with "Manage" call-to-action text', () => {
    const actionElements = fixture.nativeElement.querySelectorAll('.card-action');
    expect(actionElements).toHaveLength(5);
    expect(actionElements[0].textContent.trim()).toContain('Manage Companies');
    expect(actionElements[1].textContent.trim()).toContain('Manage Countries');
  });

  it('should render card icons with correct Material icon names', () => {
    const icons: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-icon mat-icon');
    expect(icons).toHaveLength(5);

    const iconNames = Array.from(icons).map((el: Element) =>
      el.getAttribute('data-mat-icon-name'),
    );
    expect(iconNames).toContain('business');
    expect(iconNames).toContain('public');
    expect(iconNames).toContain('location_on');
    expect(iconNames).toContain('map');
    expect(iconNames).toContain('store');
  });

  it('should render no "Coming soon" badge when no cards are disabled', () => {
    const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
    expect(comingSoonBadges).toHaveLength(0);
  });
});
