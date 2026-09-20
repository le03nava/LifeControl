import { ComponentFixture, TestBed } from '@angular/core/testing';
import { StatusChip, type StatusChipFamily } from './status-chip';

describe('StatusChip', () => {
  let fixture: ComponentFixture<StatusChip>;
  let component: StatusChip;

  async function setup(statusName: string, family?: StatusChipFamily): Promise<void> {
    await TestBed.configureTestingModule({ imports: [StatusChip] }).compileComponents();
    fixture = TestBed.createComponent(StatusChip);
    fixture.componentRef.setInput('statusName', statusName);
    if (family) {
      fixture.componentRef.setInput('family', family);
    }
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('should render the Spanish label for a known status', async () => {
    await setup('In Transit');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('En Tránsito');
  });

  it('should fall back to the raw name for unknown statuses', async () => {
    await setup('Unknown');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Unknown');
    expect(component.label()).toBe('Unknown');
  });

  it('should expose the palette color for the status', async () => {
    await setup('Draft');
    expect(component.color()).toBe('#9e9e9e');
  });

  describe('family', () => {
    it('should default to the order family, keeping existing behaviour', async () => {
      await setup('Draft');
      expect(component.family()).toBe('order');
      expect(component.label()).toBe('Borrador');
      expect(component.color()).toBe('#9e9e9e');
    });

    it('should not translate an order-detail status in the order family', async () => {
      await setup('Pending');
      expect(component.label()).toBe('Pending');
      expect(component.color()).toBe('#9e9e9e');
    });

    it('should resolve the receipt family label and color', async () => {
      await setup('Registered', 'receipt');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Registrado');
      expect(component.label()).toBe('Registrado');
      expect(component.color()).toBe('#4caf50');
    });

    it('should not translate an order status in the receipt family', async () => {
      await setup('Draft', 'receipt');
      expect(component.label()).toBe('Draft');
      expect(component.color()).toBe('#9e9e9e');
    });

    it('should resolve the detail family label and color', async () => {
      await setup('Pending', 'detail');
      expect(component.label()).toBe('Pendiente');
      expect(component.color()).toBe('#9e9e9e');
    });

    it('should fall back to the raw name for an unknown status in the receipt family', async () => {
      await setup('Nonsense', 'receipt');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nonsense');
      expect(component.label()).toBe('Nonsense');
      expect(component.color()).toBe('#9e9e9e');
    });

    it('should fall back to the raw name for an unknown status in the detail family', async () => {
      await setup('Nonsense', 'detail');
      expect(component.label()).toBe('Nonsense');
      expect(component.color()).toBe('#9e9e9e');
    });
  });
});
