import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PageHeader } from '@shared/ui';

/** One catalog the configuration area indexes. Adding a catalog is adding one of these, not a menu entry. */
interface ConfigurationCard {
  title: string;
  icon: string;
  description: string;
  route: string;
}

/**
 * The catalogs this area indexes, in the order they are shown.
 *
 * Impuestos is first because it is the one catalog that already answers a refusal an operator hits in
 * production: a purchase line whose code has no rate applicable on the document's date is refused, and until
 * this screen existed the only writer was SQL (`odd/tasks/configuration-tax-rates.md`, L2). Currencies is the
 * next card the area is expected to carry, and it needs nothing from this file's shape.
 */
const STATIC_CARDS: ConfigurationCard[] = [
  {
    title: 'Impuestos',
    icon: 'percent',
    description: 'Las tasas que la empresa declara, por código de impuesto y por región.',
    route: '/configuration/taxes',
  },
];

/**
 * The `Configuración` landing: a grid of cards, one per catalog, each a destination (`S1`).
 *
 * **No per-card role check.** The route gates the pair the writes need, so every card rendered here is a
 * destination the caller can actually open. Copying `CompaniesAdminComponent`'s `requiredRoles` per card
 * would add a second gate that could disagree with the route — the failure this landing exists to avoid
 * when a catalog is added later.
 */
@Component({
  selector: 'app-configuration-landing',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatCardModule, MatIconModule, PageHeader],
  templateUrl: './configuration-landing.component.html',
  styleUrl: './configuration-landing.component.scss',
})
export class ConfigurationLanding {
  readonly cards = (): ConfigurationCard[] => STATIC_CARDS;
}
