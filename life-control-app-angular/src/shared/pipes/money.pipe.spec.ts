import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MoneyPipe, MONEY_DIGITS_INFO } from './money.pipe';

/**
 * ADR-0002 decision 1 (spec S2 of `odd/tasks/money-first-class.md`): amounts are `NUMERIC(19,4)` and the
 * currency decides the display. The pipe is the one place the sales surface formats money, so these cases are
 * the money display contract, not formatting trivia.
 */
describe('MoneyPipe', () => {
  function createPipe(locale = 'en-US'): MoneyPipe {
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: locale }] });
    return TestBed.runInInjectionContext(() => new MoneyPipe());
  }

  it('should format with the currency it is given, not with a global default', () => {
    const pipe = createPipe('en-US');

    // The rendered form carries the code it was given, so a wrong code cannot pass for a right one. Asserting
    // the two differ is what proves the pipe obeys its argument instead of a global default: the same amount
    // rendered twice with the same everything but the currency cannot land on the same string.
    const mxn = pipe.transform(1234.5678, 'MXN');
    const usd = pipe.transform(1234.5678, 'USD');

    expect(mxn).toContain('MXN');
    expect(usd).toContain('USD');
    expect(mxn).not.toBe(usd);
  });

  it('should always show four decimals so a part that rounds to zero is still visible', () => {
    const pipe = createPipe('en-US');

    // A ten-thousandth is the smallest grain the columns carry: printing 0.00 here would make the parts stop
    // adding up to the total the same document states (S2: round once, at the document boundary, never per line).
    expect(pipe.transform(0.004, 'USD')).toContain('0.0040');
    expect(pipe.transform(0, 'USD')).toContain('0.0000');
    expect(pipe.transform(10, 'USD')).toContain('10.0000');
  });

  it('should state the absence when no currency is given instead of falling back to one', () => {
    const pipe = createPipe('en-US');

    // The whole point of T8 is that nothing prints a currency it was not given. The default token would answer
    // `MX$` here without the caller saying so, which is the silent-default failure class of `D-c`.
    const rendered = pipe.transform(1234.5678, '');
    expect(rendered).toContain('1,234.5678');
    expect(rendered).toContain('no currency');
    expect(rendered).not.toContain('$');
  });

  it('should render nothing for an absent amount', () => {
    const pipe = createPipe('en-US');

    expect(pipe.transform(null, 'USD')).toBeNull();
    expect(pipe.transform(undefined, 'USD')).toBeNull();
    expect(pipe.transform(Number.NaN, 'USD')).toBeNull();
  });

  it('should export the digits info it promises as a constant', () => {
    expect(MONEY_DIGITS_INFO).toBe('1.4-4');
  });
});
