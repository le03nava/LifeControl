import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ConfigurationLanding } from './configuration-landing.component';

describe('ConfigurationLanding', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<ConfigurationLanding>>;
  let component: ConfigurationLanding;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ConfigurationLanding],
      providers: [provideRouter([])],
    });
    fixture = TestBed.createComponent(ConfigurationLanding);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('offers the tax rates as the landing card, and names the route it opens', () => {
    expect(component.cards().map((card) => card.route)).toEqual(['/configuration/taxes']);
    expect(component.cards()[0].title).toBe('Impuestos');
  });

  it('renders one card per configuration catalog, each one a destination', () => {
    const cards = fixture.nativeElement.querySelectorAll('mat-card');

    expect(cards.length).toBe(1);
    expect((cards[0] as HTMLElement).textContent).toContain('Impuestos');
  });
});
