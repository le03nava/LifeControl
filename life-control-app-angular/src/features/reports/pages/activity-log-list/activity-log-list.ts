import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { httpErrorMessage } from '@shared/data';
import { PageHeader } from '@shared/ui';
import { ActivityLogService } from '../../data/activity-log.service';
import { ActivityLogEntry, ActivityLogFilters } from '../../models/activity-log.models';

/** The debounce window for the free-text filters, matching the app's list pattern. */
const FILTER_DEBOUNCE_MS = 300;

/** The first-read page size; also the paginator's default. */
const DEFAULT_PAGE_SIZE = 25;

/**
 * The `Reportes` screen: the audit trail, read from `GET /api/activity-logs`.
 *
 * It is the admin-only read of `activity_logs` (S3). One row per entry shows
 * `createdAt`, the actor, `process`, `event`, the HTTP method and status, the
 * request path and the IP. The actor cell falls back from `username` to `userId`
 * and to an em dash when both are absent, because the log is immutable and may
 * outlive the account it names.
 *
 * **Pagination is server-side**: `pageIndex`/`pageSize` are the query's window,
 * the answer's `totalElements` is the paginator's `length`, and the page never
 * slices the array it received.
 *
 * The filters are exactly the six the endpoint accepts — date range (`from`,
 * `to`), `process`, `event`, `userId` and HTTP method — and no more. The date
 * range and the method are discrete choices and re-read immediately; the three
 * free-text filters are debounced so a keystroke never issues a request. A
 * filter change re-reads from page 0, so a window beyond the new result set is
 * never requested.
 */
@Component({
  selector: 'app-activity-log-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatSelectModule,
    MatTableModule,
    PageHeader,
  ],
  templateUrl: './activity-log-list.html',
  styleUrl: './activity-log-list.scss',
})
export class ActivityLogList {
  private readonly activityLogService = inject(ActivityLogService);

  // Server-side pagination: these two signals are the query's window.
  readonly pageIndex = signal(0);
  readonly pageSize = signal(DEFAULT_PAGE_SIZE);

  // Discrete filters: they re-read as soon as they change.
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly httpMethod = signal('');

  // Free-text filters: edited on every keystroke, read only after the debounce.
  readonly process = signal('');
  readonly event = signal('');
  readonly userId = signal('');
  private readonly debouncedProcess = signal('');
  private readonly debouncedEvent = signal('');
  private readonly debouncedUserId = signal('');

  /** The six filters, each one present only when it carries a value. */
  readonly filters = computed<ActivityLogFilters>(() => {
    const filters: ActivityLogFilters = {};

    const from = this.fromDate().trim();
    if (from) {
      filters.from = from;
    }
    const to = this.toDate().trim();
    if (to) {
      filters.to = to;
    }
    const process = this.debouncedProcess();
    if (process) {
      filters.process = process;
    }
    const event = this.debouncedEvent();
    if (event) {
      filters.event = event;
    }
    const userId = this.debouncedUserId();
    if (userId) {
      filters.userId = userId;
    }
    const httpMethod = this.httpMethod().trim();
    if (httpMethod) {
      filters.httpMethod = httpMethod;
    }

    return filters;
  });

  private readonly pageResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      filters: this.filters(),
    }),
    stream: ({ params }) =>
      this.activityLogService.search(params.page, params.size, params.filters),
  });

  readonly entries = computed<ActivityLogEntry[]>(() =>
    this.pageResource.hasValue() ? this.pageResource.value().content : [],
  );

  /** Feeds the paginator's `length`: it is the server's total, not the page's size. */
  readonly totalElements = computed(() =>
    this.pageResource.hasValue() ? this.pageResource.value().totalElements : 0,
  );

  readonly loading = this.pageResource.isLoading;
  readonly error = computed(() => {
    const error = this.pageResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  readonly displayedColumns = [
    'createdAt',
    'username',
    'process',
    'event',
    'httpMethod',
    'httpStatus',
    'requestPath',
    'ipAddress',
  ];

  readonly pageSizeOptions = [10, 25, 50, 100];

  /** The HTTP methods the audit aspect records, offered as the method filter. */
  readonly httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

  constructor() {
    // Debounce the three free-text filters: setting an unchanged value is a no-op
    // for the computed filters, so the initial tick issues no extra read.
    effect((onCleanup) => {
      const process = this.process().trim();
      const event = this.event().trim();
      const userId = this.userId().trim();
      const timer = setTimeout(() => {
        this.debouncedProcess.set(process);
        this.debouncedEvent.set(event);
        this.debouncedUserId.set(userId);
      }, FILTER_DEBOUNCE_MS);
      onCleanup(() => clearTimeout(timer));
    });

    // A filter change re-reads from the first page. `pageIndex` is read untracked on
    // purpose: without that, the effect would track it, re-run on every page change
    // and reset the very page the paginator just asked for.
    effect(() => {
      this.filters();
      if (untracked(this.pageIndex) !== 0) {
        this.pageIndex.set(0);
      }
    });
  }

  /** The actor cell: username, then the subject id, then an em dash when neither exists. */
  actorLabel(entry: ActivityLogEntry): string {
    return entry.username ?? entry.userId ?? '—';
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  retry(): void {
    this.pageResource.reload();
  }
}
