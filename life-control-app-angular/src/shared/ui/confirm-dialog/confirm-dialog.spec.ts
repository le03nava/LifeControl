import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ConfirmDialog, type ConfirmDialogData } from './confirm-dialog';

describe('ConfirmDialog', () => {
  let fixture: ComponentFixture<ConfirmDialog>;
  let component: ConfirmDialog;

  const baseData: ConfirmDialogData = {
    title: 'Cambios sin guardar',
    message: 'Si salís ahora, se van a perder.',
  };

  async function setup(data: ConfirmDialogData): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ConfirmDialog, NoopAnimationsModule],
      providers: [{ provide: MAT_DIALOG_DATA, useValue: data }],
    }).compileComponents();

    fixture = TestBed.createComponent(ConfirmDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('should create', async () => {
    await setup(baseData);
    expect(component).toBeTruthy();
  });

  it('should render title and message', async () => {
    await setup(baseData);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Cambios sin guardar');
    expect(el.textContent).toContain('Si salís ahora, se van a perder.');
  });

  it('should use default labels when none are provided', async () => {
    await setup(baseData);
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    expect(buttons[0].textContent).toContain('Cancelar');
    expect(buttons[1].textContent).toContain('Confirmar');
  });

  it('should use custom labels when provided', async () => {
    await setup({
      ...baseData,
      cancelLabel: 'Seguir editando',
      confirmLabel: 'Salir sin guardar',
    });
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    expect(buttons[0].textContent).toContain('Seguir editando');
    expect(buttons[1].textContent).toContain('Salir sin guardar');
  });
});
