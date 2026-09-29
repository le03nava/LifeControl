/**
 * Client mirror of the server's appointment status family and transition map.
 *
 * `APPOINTMENT_TRANSITIONS` is a **declared transcription** of
 * `SchedulingAppointmentService.java:102-107`, not a value derived from any
 * endpoint: no endpoint exposes the map (`GET /statuses` serves a flat catalogue
 * and `StatusTypeResponse` carries no code or slug), so a client that does not
 * mirror it can only offer every status or none. Keeping the server anchor in
 * this comment is what makes a server-side edge or rename a review-visible
 * drift instead of a silent one; the record declares that bound as G32, and
 * {@link allowedTransitionNames} expresses it fail-closed as G40.
 */

/** The status type name the appointment statuses belong to (`D8`, `D23`). */
export const APPOINTMENT_STATUS_TYPE_NAME = 'APPOINTMENT';

/**
 * The transition map keyed by the current status **name**, transcribed from the
 * server's `APPOINTMENT_TRANSITIONS` (`SchedulingAppointmentService.java:102-107`).
 *
 * Keys are the server's five names exactly, as `V18` seeds them: `Scheduled`,
 * `Confirmed`, `Completed`, `Cancelled`, `NoShow`. A terminal status maps to an
 * empty list, and so does a name this map does not know.
 */
export const APPOINTMENT_TRANSITIONS: Readonly<Record<string, readonly string[]>> = {
  Scheduled: ['Confirmed', 'Completed', 'Cancelled', 'NoShow'],
  Confirmed: ['Completed', 'Cancelled', 'NoShow'],
  Completed: [],
  Cancelled: [],
  NoShow: [],
};

/**
 * Spanish state nouns, one per status — this is the current-status chip's copy.
 *
 * It is a **separate map** from {@link APPOINTMENT_TRANSITION_LABELS} because a
 * state noun is not an action verb: the chip says "Agendado" while the button
 * that moves into that state says "Confirmar". Folding them into one map would
 * either label a chip with a verb or a button with a state.
 */
export const APPOINTMENT_STATUS_LABELS: Readonly<Record<string, string>> = {
  Scheduled: 'Agendado',
  Confirmed: 'Confirmado',
  Completed: 'Completado',
  Cancelled: 'Cancelado',
  NoShow: 'Ausente',
};

/**
 * Spanish action verbs for the transition buttons, one per **target** status —
 * the four names that can be reached from somewhere, never `Scheduled` (nothing
 * transitions into it; booking creates it).
 *
 * The verbs are deliberately different strings from the state nouns (see
 * {@link APPOINTMENT_STATUS_LABELS}), so a button reads as an action and a chip
 * reads as a state.
 */
export const APPOINTMENT_TRANSITION_LABELS: Readonly<Record<string, string>> = {
  Confirmed: 'Confirmar',
  Completed: 'Completar',
  Cancelled: 'Cancelar',
  NoShow: 'Marcar ausente',
};

/**
 * The statuses reachable from `currentStatusName`, or an empty list when the
 * name is unknown or terminal.
 *
 * Fail-closed is deliberate (G40): a status the mirror does not know offers no
 * action rather than every status. `undefined` and "every status" are both
 * wrong — the first breaks the render, the second guarantees a 409 on the
 * statuses the server refuses.
 */
export function allowedTransitionNames(currentStatusName: string): readonly string[] {
  // `Object.hasOwn` guards the prototype chain: a bare lookup would let a name like
  // `'constructor'` reach `Object.prototype`, so `?? []` would never fire and the mirror
  // would fail open. The server is `getOrDefault(currentStatusName, Set.of()).isEmpty()`
  // (`SchedulingAppointmentService.java:533`), terminal for every missing key.
  return Object.hasOwn(APPOINTMENT_TRANSITIONS, currentStatusName)
    ? APPOINTMENT_TRANSITIONS[currentStatusName]
    : [];
}

/**
 * Whether `currentStatusName` has no outgoing transition.
 *
 * Mirrors the server's own default: a missing key or an empty set means
 * terminal, so a name the mirror does not know is terminal too (G40).
 */
export function isTerminalStatus(currentStatusName: string): boolean {
  return allowedTransitionNames(currentStatusName).length === 0;
}
