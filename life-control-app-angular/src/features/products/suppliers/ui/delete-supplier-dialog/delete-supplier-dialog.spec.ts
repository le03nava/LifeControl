import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { DeleteSupplierDialogComponent } from './delete-supplier-dialog';

describe('DeleteSupplierDialogComponent', () => {
  let component: DeleteSupplierDialogComponent;
  let fixture: ComponentFixture<DeleteSupplierDialogComponent>;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const mockData = { supplierName: 'Acme Supplies' };

  beforeEach(async () => {
    dialogRef = { close: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [DeleteSupplierDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: mockData },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DeleteSupplierDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should receive supplier name from data', () => {
    expect(component.data.supplierName).toBe('Acme Supplies');
  });

  it('should render supplier name in template', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Acme Supplies');
  });

  it('should render cancel and delete buttons', () => {
    const el = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toContain('Cancelar');
    expect(buttons[1].textContent).toContain('Eliminar');
  });
});
