import { ComponentFixture, TestBed } from '@angular/core/testing';
import { StatusChip } from './status-chip';

describe('StatusChip', () => {
  let fixture: ComponentFixture<StatusChip>;
  let component: StatusChip;

  async function setup(statusName: string): Promise<void> {
    await TestBed.configureTestingModule({ imports: [StatusChip] }).compileComponents();
    fixture = TestBed.createComponent(StatusChip);
    fixture.componentRef.setInput('statusName', statusName);
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
});
