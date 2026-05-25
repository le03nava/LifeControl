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

  it('should have routerLink on the Companies (active) card', () => {
    const activeCard = fixture.debugElement.query(
      By.css('.dashboard-card:not(.card--disabled)'),
    );
    expect(activeCard).toBeTruthy();
    const routerLinkDirective = activeCard.injector.get(RouterLink);
    expect(routerLinkDirective).toBeTruthy();
  });

  it('should NOT have valid routerLink on placeholder cards (countries, regions, zones, branches)', () => {
    const disabledCards = fixture.debugElement.queryAll(
      By.css('.dashboard-card.card--disabled'),
    );
    // 4 disabled cards
    expect(disabledCards).toHaveLength(4);
    // Disabled cards have RouterLink directive but with null/falsy route
    for (const card of disabledCards) {
      const link = card.injector.get(RouterLink);
      expect(link.routerLink).toBeFalsy();
    }
  });

  it('should display "Coming soon" on each disabled card', () => {
    const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
    // 4 disabled cards each have a coming-soon badge
    expect(comingSoonBadges).toHaveLength(4);
    comingSoonBadges.forEach((badge: Element) => {
      expect(badge.textContent.trim()).toContain('Coming soon');
    });
  });

  it('should render each placeholder card with correct title from data', () => {
    const cardTitles: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-title');
    const titles = Array.from(cardTitles).map((el: Element) => el.textContent?.trim() ?? '');

    expect(titles).toContain('Companies');
    expect(titles).toContain('Countries');
    expect(titles).toContain('Regions');
    expect(titles).toContain('Zones');
    expect(titles).toContain('Branches');
  });

  it('should render the dashboard subtitle', () => {
    const subtitle = fixture.nativeElement.querySelector('.dashboard-subtitle');
    expect(subtitle).toBeTruthy();
    expect(subtitle.textContent.trim()).toBe(
      'Manage companies, countries, regions, zones, and branches',
    );
  });

  it('should render active card with "Manage" call-to-action text', () => {
    const actionElements = fixture.nativeElement.querySelectorAll('.card-action');
    expect(actionElements).toHaveLength(1);
    expect(actionElements[0].textContent.trim()).toContain('Manage Companies');
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
    expect(iconNames).toContain('account_balance');
  });

  it('should render 4 "Coming soon" badges (one per disabled card)', () => {
    const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
    expect(comingSoonBadges).toHaveLength(4);
  });
});
