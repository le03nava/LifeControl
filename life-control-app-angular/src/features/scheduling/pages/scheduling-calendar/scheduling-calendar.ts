import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { NotificationService } from '@shared/data/notification';
import { hasAnyClientRole, SCHEDULING_WRITE_ROLES } from '@core/security/roles';
import { SchedulingWeekGrid } from '../../components/scheduling-week-grid/scheduling-week-grid';
import { SchedulingDayAgenda } from '../../components/scheduling-day-agenda/scheduling-day-agenda';
import {
  SchedulingAppointmentDialog,
  SchedulingAppointmentDialogData,
  SchedulingAppointmentDialogResult,
} from '../../components/scheduling-appointment-dialog/scheduling-appointment-dialog';
import {
  SchedulingCalendarService,
  SchedulingCatalogueTooLargeError,
} from '../../data/scheduling-calendar.service';
import { SchedulingStoreContext } from '../../data/scheduling-store-context.service';
import {
  addDays,
  buildWeek,
  formatWeekLabel,
  parseAnchorDate,
  startOfIsoWeek,
  toIsoDate,
  toIsoMidnight,
} from '../../data/scheduling-calendar-week';
import {
  SchedulingCalendarWeekRequest,
  SchedulingCalendarEntry,
} from '../../models/scheduling-calendar.models';
import { SchedulingActivity } from '../../models/scheduling-activity.models';

/**
 * Walks the `cause` chain (guarding against cycles) to find the catalogue-cap
 * error rxResource may wrap. Any other error stays with `httpErrorMessage`.
 */
function unwrapCatalogueError(error: unknown): SchedulingCatalogueTooLargeError | null {
  let current: unknown = error;
  const visited = new Set<unknown>();
  while (current != null && !visited.has(current)) {
    if (current instanceof SchedulingCatalogueTooLargeError) {
      return current;
    }
    visited.add(current);
    current = (current as { cause?: unknown }).cause;
  }
  return null;
}

/**
 * The read-only week calendar: a seven-column grid of the store's bookable slots
 * plus the selected day's agenda, both built from **one** `GET /calendar`
 * response (D54).
 *
 * The page is reachable for the read role set (D60, D61) and owns the write path
 * (D71): the grid emits the slot that was chosen, this page opens the appointment
 * dialog and, on a booked or stale result, re-reads the week (D72). A reader sees
 * no booking affordance at all (D65, D74).
 *
 * The week read is a chain the service owns (D50): read the store's enabled
 * activities, fan out the materializing `GET /slots` per activity at a bounded
 * concurrency (D55), then read the projection. A failure anywhere in the
 * materialization fails the whole week closed (D59), because a week missing one
 * activity's slots is indistinguishable from that activity having none.
 *
 * Four states are rendered distinctly (D58): `storePending`, `storeError`,
 * `storeUnconfigured` and `weekWithoutSlots` — plus the materialization failure,
 * which is a different fact again.
 */
@Component({
  selector: 'app-scheduling-calendar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Per screen: this page owns its own store resolution.
  providers: [SchedulingStoreContext],
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    PageHeader,
    ErrorBanner,
    SchedulingWeekGrid,
    SchedulingDayAgenda,
  ],
  templateUrl: './scheduling-calendar.html',
  styleUrl: './scheduling-calendar.scss',
})
export class SchedulingCalendar {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly calendarService = inject(SchedulingCalendarService);
  private readonly storeContext = inject(SchedulingStoreContext);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-scheduling-read` reaches this page but the backend answers 403 on every
   * write, so no booking affordance is rendered for it (D74). Read once, as the
   * sibling pages do; the grid receives the value and reads no role itself.
   */
  readonly canWrite = hasAnyClientRole(SCHEDULING_WRITE_ROLES);

  /**
   * The route's query params **as a stream** (D64), never from the snapshot.
   *
   * Week navigation is intra-route: the next week and the store switch keep this
   * component alive, so a snapshot read would keep rendering the first week
   * forever while the URL said otherwise (G26). The snapshot is only the first
   * value, so the very first render already has the right week.
   */
  private readonly queryParams = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  readonly storePending = this.storeContext.pending;
  readonly storeError = this.storeContext.storeError;
  readonly storeUnconfigured = this.storeContext.unconfigured;
  readonly storeId = this.storeContext.storeId;

  /** The anchor date of the visible week: `?date=`, or today when absent/invalid. */
  readonly anchorDate = computed(() => parseAnchorDate(this.queryParams().get('date')));

  /** The Monday `00:00` local that anchors the visible ISO week (D53). */
  readonly weekStart = computed(() => startOfIsoWeek(this.anchorDate()));

  readonly rangeFrom = computed(() => toIsoMidnight(this.weekStart()));
  /** Exclusive upper bound: the next Monday `00:00` (D53). */
  readonly rangeTo = computed(() => toIsoMidnight(addDays(this.weekStart(), 7)));

  readonly weekLabel = computed(() => formatWeekLabel(this.weekStart()));

  /**
   * The activity filter (D52). The `userId` filter is deliberately not exposed:
   * there is no authorized employee source, so it would be a text box where the
   * operator types a Keycloak `sub` from memory (G21).
   */
  readonly activityFilter = signal<string | null>(null);

  /**
   * The filter's options, read once per store.
   *
   * A resource of its own, independent from the week read, so the options do not
   * disappear while a week reloads. It is not the week read's source of activities:
   * that read is self-contained, which is what keeps a week change from depending
   * on another resource having already emitted.
   */
  readonly activitiesResource = rxResource({
    params: () => {
      const storeId = this.storeId();
      return storeId ? { storeId } : undefined;
    },
    stream: ({ params }) => this.calendarService.listEnabledActivities(params.storeId),
  });

  readonly activities = computed<SchedulingActivity[]>(() =>
    this.activitiesResource.hasValue() ? this.activitiesResource.value() : [],
  );

  /**
   * The week read: materialize first, project second.
   *
   * The params carry the visible range, so an intra-route week change (a new
   * `?date=`) re-runs the whole chain for the new range.
   */
  readonly weekResource = rxResource({
    params: (): SchedulingCalendarWeekRequest | undefined => {
      const storeId = this.storeId();
      if (!storeId) {
        return undefined;
      }
      return {
        storeId,
        from: this.rangeFrom(),
        to: this.rangeTo(),
        activityId: this.activityFilter(),
      };
    },
    stream: ({ params }) => this.calendarService.loadCalendarWeek(params),
  });

  private readonly weekEntries = computed<SchedulingCalendarEntry[]>(() =>
    this.weekResource.hasValue() ? this.weekResource.value() : [],
  );

  /**
   * Whether nothing has settled yet, **or** a reload is in flight.
   *
   * Deliberately not `isLoading()` alone: that flag turns true one reactive step
   * after the params are defined, so a state gate built only on it can be
   * evaluated before the load starts, see `false`, and render an empty grid
   * instead of the loading state. Settling on `isLoading() || !hasValue()` is
   * order-independent, and it also covers `reload()` — which keeps the previous
   * stream, so `hasValue()` stays `true` and the stale empty state would otherwise
   * render while the retry is still in flight (F4).
   */
  readonly weekPending = computed(() => {
    if (this.weekResource.error() !== undefined) {
      return false;
    }
    return this.weekResource.isLoading() || !this.weekResource.hasValue();
  });

  /**
   * A settled read that failed, including a materialization failure (D59).
   *
   * The catalogue cap is a failure this page states in its own words: nothing
   * failed on the wire, the store simply has more enabled activities than the
   * week read will page through, and a generic transport message would hide that.
   */
  readonly weekError = computed(() => {
    const error = this.weekResource.error();
    if (!error) {
      return null;
    }
    return unwrapCatalogueError(error)?.message ?? httpErrorMessage(error);
  });

  /**
   * The number of entries the grid would actually draw.
   *
   * Counted over `weekDays()`, not over the raw response: `buildWeek` drops an
   * entry whose slot starts outside the visible week, so counting the raw array
   * could show the empty state over a non-empty response, or — worse — an empty
   * seven-column grid while the empty state stayed hidden (F5).
   */
  private readonly visibleEntryCount = computed(() =>
    this.weekDays().reduce((total, day) => total + day.entries.length, 0),
  );

  /**
   * A settled read that legitimately returned nothing (D58).
   *
   * The page prepares the week from each activity's availability before it draws
   * it, so an empty week means no availability covers the range — not that the read
   * is stale. The filter is the first thing to check.
   */
  readonly weekWithoutSlots = computed(
    () => this.weekResource.hasValue() && this.visibleEntryCount() === 0,
  );

  readonly weekDays = computed(() => buildWeek(this.weekStart(), this.weekEntries()));

  /** The day the agenda shows; null means "the anchor day". */
  readonly selectedDate = signal<string | null>(null);

  /**
   * The day actually shown: the explicit selection while it belongs to the
   * visible week, otherwise the anchor day (today by default), so navigating to
   * another week can never leave the agenda on a day that is not displayed.
   */
  readonly effectiveSelectedDate = computed(() => {
    const days = this.weekDays();
    const selected = this.selectedDate();
    if (selected && days.some((day) => day.date === selected)) {
      return selected;
    }
    const anchor = toIsoDate(this.anchorDate());
    return days.some((day) => day.date === anchor) ? anchor : (days[0]?.date ?? '');
  });

  readonly selectedDayEntries = computed<SchedulingCalendarEntry[]>(
    () => this.weekDays().find((day) => day.date === this.effectiveSelectedDate())?.entries ?? [],
  );

  /**
   * The store the current filter was chosen in; `null` until a store resolves.
   *
   * A plain field, not a signal: it only records which store a filter belongs to,
   * it is never rendered.
   */
  private filterStoreId: string | null = null;

  constructor() {
    // The store id travels as a stream too (D64); `resolve` is idempotent for the
    // same input, so this does not re-run the resolution on every emission.
    effect(() => {
      this.storeContext.resolve(this.queryParams().get('storeId'));
    });

    // A filter chosen in one store must not outlive it. Switching `?storeId=`
    // re-runs the resolution, and a filter naming the previous store's activity
    // would render a blank selection (no matching option in the new store's list)
    // over an empty week — the filter naming one store while the page shows
    // another. Clearing it on the store change makes that state unreachable.
    effect(() => {
      const storeId = this.storeId();
      if (storeId !== this.filterStoreId) {
        this.filterStoreId = storeId;
        this.activityFilter.set(null);
      }
    });
  }

  /** Narrows the week read to one activity, fan-out and projection alike (D63). */
  onActivityFilterChange(activityId: string | null): void {
    this.activityFilter.set(activityId);
  }

  onDaySelected(date: string): void {
    this.selectedDate.set(date);
  }

  /**
   * Opens the booking dialog for a slot the grid offered (D71, D74).
   *
   * The affordance is already gated on both this page's `canWrite` and the grid's
   * own `activityEnabled`/`available` terms, so this guard is defence in depth:
   * nothing that cannot complete the write opens a dialog.
   *
   * The dialog performs **no read** of its own, so the attending employee's
   * `userId` is resolved here from the catalogue this page already holds (the
   * per-store activity list). An activity absent from that list contributes `null`
   * and the dialog's field stays empty and editable (D30, D45).
   */
  onSlotSelected(entry: SchedulingCalendarEntry): void {
    if (!this.canWrite) {
      return;
    }

    const activityUserId =
      this.activities().find((activity) => activity.id === entry.activityId)?.userId ?? null;

    this.dialog
      .open<
        SchedulingAppointmentDialog,
        SchedulingAppointmentDialogData,
        SchedulingAppointmentDialogResult
      >(SchedulingAppointmentDialog, { data: { entry, activityUserId } })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.onBookingClosed(result));
  }

  /**
   * Reacts to the dialog's close (D72).
   *
   * `undefined` is treated as no result on purpose: Material can close the ref
   * itself (Esc or backdrop) and that close carries no result, so it means the
   * same thing as `null` — no write and no conflict.
   */
  private onBookingClosed(result: SchedulingAppointmentDialogResult | undefined): void {
    if (result?.outcome === 'booked') {
      this.notifications.showSuccess('Turno reservado correctamente.');
      this.weekResource.reload();
      return;
    }

    // A stale verdict is already stated in the dialog's own copy, so the page adds no
    // toast of its own: the re-read is the remedy. The interceptor's toast for the
    // failed write is a separate signal and it already fired; this branch stays silent
    // to avoid a page-level toast on top of the dialog's copy, not to claim that no
    // toast happened.
    if (result?.outcome === 'stale') {
      this.weekResource.reload();
    }
  }

  /** A new `?date=` merged onto the current params, so `storeId` survives (D64). */
  private navigateToWeek(date: Date): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { date: toIsoDate(startOfIsoWeek(date)) },
      queryParamsHandling: 'merge',
    });
  }

  onPreviousWeek(): void {
    this.navigateToWeek(addDays(this.weekStart(), -7));
  }

  onNextWeek(): void {
    this.navigateToWeek(addDays(this.weekStart(), 7));
  }

  onToday(): void {
    // `new Date()` reads the current instant, it is not date arithmetic, which is
    // why it lives here and not in the pure week helper (D56); the anchor itself is
    // normalized to the week's Monday by `navigateToWeek`.
    this.navigateToWeek(new Date());
  }

  /** Re-runs a store resolution that failed: a failed read is not "no store set". */
  retryStore(): void {
    this.storeContext.reload();
  }

  /** Re-runs a week read that failed. */
  retryWeek(): void {
    this.weekResource.reload();
  }
}
