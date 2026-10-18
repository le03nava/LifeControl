import { Component } from '@angular/core';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HrAdmin } from './hr-admin';

/** Stand-in for the lazy catalog routes: the card's click must land on a real URL. */
@Component({ selector: 'app-hr-admin-route-stub', template: '' })
class RouteStub {}

/**
 * The `/hr` landing page. It is the destination of the `Recursos Humanos` header
 * entry, and it owns the three catalogs the menu used to list as a submenu, so
 * these assertions pin the same set, the same order and the same destinations the
 * panel pinned.
 */
describe('HrAdmin', () => {
  let fixture: ComponentFixture<HrAdmin>;
  let component: HrAdmin;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HrAdmin],
      providers: [provideRouter([{ path: '**', component: RouteStub }])],
    });

    fixture = TestBed.createComponent(HrAdmin);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  const cards = (): HTMLElement[] =>
    Array.from(fixture.nativeElement.querySelectorAll('.dashboard-card') as NodeListOf<HTMLElement>);

  it('should expose the three HR catalogs in the order the submenu listed them', () => {
    expect(component.cards.map((card) => card.title)).toEqual([
      'Departamentos',
      'Puestos',
      'Empleados',
    ]);
  });

  it('should render one card per catalog, with its title', () => {
    const titles = Array.from(
      fixture.nativeElement.querySelectorAll('.card-title') as NodeListOf<HTMLElement>,
    ).map((el) => el.textContent?.trim() ?? '');

    expect(titles).toEqual(['Departamentos', 'Puestos', 'Empleados']);
    expect(fixture.debugElement.queryAll(By.css('.dashboard-card'))).toHaveLength(3);
  });

  it.each([
    [0, '/hr/departments'],
    [1, '/hr/positions'],
    [2, '/hr/employees'],
  ])('should navigate card %i to %s', async (index, path) => {
    // The card is the link, like the `/purchases` cards: clicking it is the only way
    // the destination is reached, so this asserts the click and not the attribute.
    cards()[index].click();
    await fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe(path);
  });

  it('should title the page for the HR surface it belongs to', () => {
    const title = fixture.nativeElement.querySelector('.page-title') as HTMLElement | null;

    expect(title?.textContent?.trim()).toBe('Recursos Humanos');
  });
});
