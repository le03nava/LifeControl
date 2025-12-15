import { trigger, state, style, transition, animate } from '@angular/animations';

export const notificationAnimation = trigger('notificationAnimation', [
  transition(':enter', [
    style({
      opacity: 0,
      transform: 'translateX(100%) translateY(-50%)',
    }),
    animate(
      '300ms ease-out',
      style({
        opacity: 1,
        transform: 'translateX(0) translateY(-50%)',
      })
    ),
  ]),
  transition(':leave', [
    animate(
      '300ms ease-in',
      style({
        opacity: 0,
        transform: 'translateX(100%) translateY(-50%)',
      })
    ),
  ]),
]);