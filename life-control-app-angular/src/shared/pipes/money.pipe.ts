import { inject, LOCALE_ID, Pipe, PipeTransform } from '@angular/core';
import { formatCurrency, formatNumber } from '@angular/common';

/**
 * The digits info every money value on the sales surface is rendered with.
 *
 * ADR-0002 decision 1 (`odd/tasks/money-first-class.md` spec S2): amounts are `NUMERIC(19,4)` and «the currency
 * decides the display and the rounding: the rounding to its `minor_units` happens **once, at the document
 * boundary** — never per line». Four decimals on every part is how that boundary stays visible: a part printed as
 * `0.00` while the document's total carries the tenthousandth is a document whose visible parts do not add up.
 */
export const MONEY_DIGITS_INFO = '1.4-4';

/**
 * Renders an amount in the currency the caller states, never in a currency the app happens to default to.
 *
 * `provide(DEFAULT_CURRENCY_CODE, 'MXN')` in `app.config.ts` is what `| currency` with no argument answers, and
 * T8 removes that answer from the sales surface: a sale's currency is its own column since `V80`, so a template
 * that prints money without being told which currency is a template that can print the wrong one silently.
 *
 * A missing currency is **named**, not guessed. The alternative — falling back to the default token — is the
 * silent-`MXN` failure class decision `D-c` of the same record forbids.
 *
 * @param amount the value to render, or `null`/`undefined`/`NaN` when there is nothing to render
 * @param currency the document's currency code (`sales_orders.currency`). Missing means «name the absence»
 * @param locale the locale to format in, normally the injected `LOCALE_ID`
 */
export function formatMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
  locale: string,
): string | null {
  if (amount === null || amount === undefined || Number.isNaN(amount)) {
    return null;
  }

  if (!currency) {
    return `${formatNumber(amount, locale, MONEY_DIGITS_INFO)} (no currency)`;
  }

  return formatCurrency(amount, locale, currency, currency, MONEY_DIGITS_INFO);
}

/** Template form of {@link formatMoney}, so a component and a template cannot disagree on the grain. */
@Pipe({ name: 'money', standalone: true })
export class MoneyPipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(amount: number | null | undefined, currency: string | null | undefined): string | null {
    return formatMoney(amount, currency, this.locale);
  }
}
