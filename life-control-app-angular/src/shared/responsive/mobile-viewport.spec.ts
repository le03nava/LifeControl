import { ChangeDetectionStrategy, Component, type Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { observeMobileViewport } from './mobile-viewport';

@Component({
  selector: 'app-mobile-viewport-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
})
class MobileViewportHost {
  readonly isMobile: Signal<boolean> = observeMobileViewport();
}

interface MatchMediaStub {
  readonly listeners: Record<string, EventListener>;
  readonly removeEventListener: ReturnType<typeof vi.fn>;
}

describe('observeMobileViewport', () => {
  let originalMatchMedia: typeof window.matchMedia;

  function setupMatchMedia(matches: boolean): MatchMediaStub {
    const listeners: Record<string, EventListener> = {};
    const removeEventListener = vi.fn();
    const mql = {
      matches,
      addEventListener: (type: string, listener: EventListener) => {
        listeners[type] = listener;
      },
      removeEventListener,
      addListener: vi.fn(),
      removeListener: vi.fn(),
    };
    window.matchMedia = vi
      .fn()
      .mockReturnValue(mql as unknown as MediaQueryList) as unknown as typeof window.matchMedia;
    return { listeners, removeEventListener };
  }

  function createHost(): ComponentFixture<MobileViewportHost> {
    const fixture = TestBed.createComponent(MobileViewportHost);
    fixture.detectChanges();
    return fixture;
  }

  beforeAll(() => {
    originalMatchMedia = window.matchMedia;
  });

  afterAll(() => {
    window.matchMedia = originalMatchMedia;
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [MobileViewportHost] }).compileComponents();
  });

  it('should start on desktop when the media query does not match', () => {
    setupMatchMedia(false);
    expect(createHost().componentInstance.isMobile()).toBe(false);
  });

  it('should start on mobile when the media query matches', () => {
    setupMatchMedia(true);
    expect(createHost().componentInstance.isMobile()).toBe(true);
  });

  it('should update the signal when the change listener fires', () => {
    const { listeners } = setupMatchMedia(false);
    const fixture = createHost();
    expect(fixture.componentInstance.isMobile()).toBe(false);

    listeners['change']({ matches: true } as MediaQueryListEvent);

    expect(fixture.componentInstance.isMobile()).toBe(true);
  });

  it('should remove the change listener when the component is destroyed', () => {
    const { listeners, removeEventListener } = setupMatchMedia(false);
    const fixture = createHost();
    const registered = listeners['change'];
    expect(registered).toBeTypeOf('function');

    fixture.destroy();

    expect(removeEventListener).toHaveBeenCalledTimes(1);
    expect(removeEventListener).toHaveBeenCalledWith('change', registered);
  });
});
