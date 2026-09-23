import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RemoveSupplierDialog } from './remove-supplier-dialog';

describe('RemoveSupplierDialog', () => {
  let component: RemoveSupplierDialog;
  let fixture: ComponentFixture<RemoveSupplierDialog>;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const mockData = { supplierName: 'Proveedor Uno' };

  beforeEach(async () => {
    dialogRef = { close: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [RemoveSupplierDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: mockData },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RemoveSupplierDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should receive the supplier name from data', () => {
    expect(component.data.supplierName).toBe('Proveedor Uno');
  });

  it('should render the supplier name in the question', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('mat-dialog-content strong')?.textContent?.trim()).toBe(
      'Proveedor Uno',
    );
  });

  it('should render every user-facing string in Rioplatense voseo', () => {
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('h2')?.textContent?.trim()).toBe(
      'Eliminar la asignación del proveedor',
    );
    expect(el.querySelector('mat-dialog-content p')?.textContent).toContain(
      '¿Estás seguro que querés eliminar la asignación de',
    );
    expect(el.querySelector('.warning-text')?.textContent?.trim()).toBe(
      'Esta acción no se puede deshacer.',
    );

    const buttons = Array.from(el.querySelectorAll('button'));
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent?.trim()).toBe('Cancelar');
    expect(buttons[1].textContent?.trim()).toBe('Eliminar');
  });
});
