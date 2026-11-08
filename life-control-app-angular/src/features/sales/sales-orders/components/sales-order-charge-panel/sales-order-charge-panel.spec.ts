import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { SalesOrderChargePanel } from './sales-order-charge-panel';
import type { PaymentMethodOption, SalePaymentRequest } from '../../models/sales-order.models';

/**
 * ADR-0002 decision 5, invariant M3/M4 and `PATCH /api/sales-orders/{id}/charge` (T7b): a charge states a list of
 * payments, each with a tender and its change, and only a cash method returns change. This panel is the surface
 * that states them.
 */
const CASH: PaymentMethodOption = { id: 'pm-cash', paymentMethodName: 'Efectivo', isCash: true };
const CARD: PaymentMethodOption = { id: 'pm-card', paymentMethodName: 'Tarjeta', isCash: false };

describe('SalesOrderChargePanel', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SalesOrderChargePanel],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();
  });

  function createFixture(overrides?: {
    total?: number;
    currency?: string;
    paymentMethods?: PaymentMethodOption[];
    charging?: boolean;
    disabled?: boolean;
  }): {
    fixture: ComponentFixture<SalesOrderChargePanel>;
    comp: SalesOrderChargePanel;
    charged: SalePaymentRequest[][];
  } {
    const fixture = TestBed.createComponent(SalesOrderChargePanel);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('total', overrides?.total ?? 150);
    fixture.componentRef.setInput('currency', overrides?.currency ?? 'USD');
    fixture.componentRef.setInput('paymentMethods', overrides?.paymentMethods ?? [CASH, CARD]);
    fixture.componentRef.setInput('charging', overrides?.charging ?? false);
    fixture.componentRef.setInput('disabled', overrides?.disabled ?? false);

    const charged: SalePaymentRequest[][] = [];
    comp.charge.subscribe((payments) => charged.push(payments));

    fixture.detectChanges();
    return { fixture, comp, charged };
  }

  describe('initial state', () => {
    it('should start with one payment carrying the document total', () => {
      const { comp } = createFixture({ total: 150 });

      const rows = comp.rows();
      expect(rows).toHaveLength(1);
      expect(rows[0].paymentMethodId).toBe('pm-cash');
      expect(rows[0].amount).toBe(150);
    });

    it('should say nothing is outstanding when the single payment covers the total', () => {
      const { comp } = createFixture({ total: 150 });
      expect(comp.outstanding()).toBe(0);
    });
  });

  describe('mixed tender', () => {
    it('should state one payment per tender, in the order they were entered', () => {
      const { comp, charged } = createFixture({ total: 150 });

      comp.updateAmount(0, 100);
      comp.addPayment();
      comp.updateMethod(1, 'pm-card');
      comp.updateAmount(1, 50);
      comp.submit();

      expect(charged).toHaveLength(1);
      expect(charged[0]).toEqual([
        { paymentMethodId: 'pm-cash', amount: 100 },
        { paymentMethodId: 'pm-card', amount: 50 },
      ]);
    });

    it('should default an added payment to what is still outstanding', () => {
      const { comp } = createFixture({ total: 150 });

      comp.updateAmount(0, 40);
      comp.addPayment();

      expect(comp.rows()[1].amount).toBe(110);
    });

    it('should report what is still owed across the tenders', () => {
      const { comp } = createFixture({ total: 150 });

      comp.updateAmount(0, 100);
      comp.addPayment();
      comp.updateAmount(1, 20);

      expect(comp.outstanding()).toBe(30);
    });
  });

  describe('change', () => {
    it('should state the tender and the change of a cash payment', () => {
      const { comp, charged } = createFixture({ total: 150 });

      comp.updateTendered(0, 200);
      comp.submit();

      expect(charged[0]).toEqual([
        { paymentMethodId: 'pm-cash', amount: 150, tenderedAmount: 200, changeAmount: 50 },
      ]);
    });

    it('should not state a tender pair for a method that is not cash', () => {
      const { comp, charged } = createFixture({ total: 150 });

      // M4: change on a non-cash method is refused by the server, so the panel never states one.
      comp.updateMethod(0, 'pm-card');
      comp.updateTendered(0, 200);
      comp.submit();

      expect(charged[0]).toEqual([{ paymentMethodId: 'pm-card', amount: 150 }]);
      expect(comp.changeFor(comp.rows()[0])).toBeNull();
    });

    it('should state neither tender nor change when no cash was counted', () => {
      const { comp, charged } = createFixture({ total: 150 });

      comp.submit();

      // Null is not zero: a drawer that was never opened is a different fact from a customer who handed over
      // nothing, and `ck_payment_tender` refuses half a pair.
      expect(charged[0]).toEqual([{ paymentMethodId: 'pm-cash', amount: 150 }]);
    });

    it('should show no change when the tender falls short of the amount', () => {
      const { comp } = createFixture({ total: 150 });

      comp.updateTendered(0, 100);

      expect(comp.changeFor(comp.rows()[0])).toBeNull();
      expect(comp.outstanding()).toBe(50);
    });
  });

  describe('the money it states', () => {
    it('should accept four decimals on amount and tender', () => {
      const { fixture } = createFixture();

      const steps = Array.from<HTMLInputElement>(
        fixture.nativeElement.querySelectorAll('input[type="number"]'),
      ).map((input) => input.getAttribute('step'));

      expect(steps).toContain('0.0001');
    });

    it('should trim float noise to the scale the columns carry', () => {
      const { comp, charged } = createFixture({ total: 0.3 });

      comp.updateAmount(0, 0.1 + 0.2);
      comp.updateTendered(0, 0.5);
      comp.submit();

      // A fifth decimal is refused by `@Digits(fraction = 4)` and would arrive as a field violation for a number
      // the operator never typed.
      expect(charged[0][0].amount).toBe(0.3);
      expect(charged[0][0].changeAmount).toBe(0.2);
    });

    it('should format what it shows with the currency it is given', () => {
      const { fixture } = createFixture({ total: 150, currency: 'MXN' });

      const text: string = fixture.nativeElement.textContent;
      expect(text).toContain('MXN');
    });
  });

  describe('when it cannot be submitted', () => {
    it('should be disabled while charging', () => {
      const { comp, charged } = createFixture({ charging: true });

      comp.submit();

      expect(charged).toHaveLength(0);
    });

    it('should be disabled when the order has no line items', () => {
      const { comp, charged } = createFixture({ disabled: true });

      comp.submit();

      expect(charged).toHaveLength(0);
    });

    it('should refuse to state a payment with no amount', () => {
      const { comp, charged } = createFixture({ total: 150 });

      comp.updateAmount(0, 0);
      comp.submit();

      expect(charged).toHaveLength(0);
    });

    it('should refuse to state a payment with no method', () => {
      const { comp, charged } = createFixture({ total: 150 });

      comp.updateMethod(0, '');
      comp.submit();

      expect(charged).toHaveLength(0);
    });
  });

  describe('the difference it reports', () => {
    it('should name the shortfall without blocking the charge', () => {
      const { comp } = createFixture({ total: 150 });

      comp.updateAmount(0, 100);
      const text = comp.differenceLabel();

      // The form is not an integrity boundary: M3 lives on the server and this line only reports. Refusing here
      // would be the client-side copy of a rule the ADR puts server-side.
      expect(text).toContain('short');
      expect(comp.canSubmit()).toBe(true);
    });

    it('should say nothing when the payments cover the total exactly', () => {
      const { comp } = createFixture({ total: 150 });
      expect(comp.differenceLabel()).toBeNull();
    });
  });

  describe('removing a payment', () => {
    it('should remove the row it is told to', () => {
      const { comp } = createFixture({ total: 150 });

      comp.addPayment();
      comp.updateMethod(1, 'pm-card');
      comp.removePayment(0);

      expect(comp.rows()).toHaveLength(1);
      expect(comp.rows()[0].paymentMethodId).toBe('pm-card');
    });

    it('should keep at least one payment row', () => {
      const { comp } = createFixture({ total: 150 });

      comp.removePayment(0);

      expect(comp.rows()).toHaveLength(1);
    });
  });
});
