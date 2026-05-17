import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Header } from './header';

describe('Header', () => {
  const setup = () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideLocationMocks()],
    });

    const fixture = TestBed.createComponent(Header);
    const component = fixture.componentInstance;

    fixture.detectChanges();

    return { fixture, component };
  };

  describe('menu items', () => {
    it('should not include expressions menu item after deletion', () => {
      const { component } = setup();
      const items = component.items();
      const expressionsItem = items.find((item) => item.routeLink === '/expressions');
      expect(expressionsItem).toBeUndefined();
    });

    it('should include home menu item', () => {
      const { component } = setup();
      const items = component.items();
      const homeItem = items.find((item) => item.routeLink === '/home');
      expect(homeItem).toBeDefined();
      expect(homeItem?.textLink).toBe('Home');
    });

    it('should include products menu item', () => {
      const { component } = setup();
      const items = component.items();
      const productsItem = items.find((item) => item.routeLink === '/products');
      expect(productsItem).toBeDefined();
      expect(productsItem?.textLink).toBe('products');
    });

    it('should include companies menu item', () => {
      const { component } = setup();
      const items = component.items();
      const companiesItem = items.find((item) => item.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
    });
  });

  describe('menu items count without admin role', () => {
    it('should have exactly 3 menu items when user is not admin', () => {
      const { component } = setup();
      expect(component.items().length).toBe(3);
    });
  });
});
