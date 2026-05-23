import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { DeleteCompanyDialogComponent } from './delete-company-dialog';

describe('DeleteCompanyDialogComponent', () => {
  let component: DeleteCompanyDialogComponent;
  let fixture: ComponentFixture<DeleteCompanyDialogComponent>;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const mockData = { companyName: 'Alpha Corp' };

  beforeEach(async () => {
    dialogRef = { close: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [DeleteCompanyDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: mockData },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DeleteCompanyDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should receive company name from data', () => {
    expect(component.data.companyName).toBe('Alpha Corp');
  });

  it('should render company name in template', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Alpha Corp');
  });

  it('should render cancel and delete buttons', () => {
    const el = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toContain('Cancelar');
    expect(buttons[1].textContent).toContain('Eliminar');
  });
});
