import { FormControl } from '@angular/forms';

export interface Product {
  id: string;
  sku: string;
  name: string;
  shortName?: string;
  satCode?: string;
  productType?: string;
  attributes?: Record<string, unknown>;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  /**
   * Deepest node of a company's product category tree this product is classified under, or `null`.
   *
   * The screen's three selects are a view of this one id: the `categoria` / `subcategoria` levels are
   * recovered by walking `parentCategoryId` up the tree the API just returned, never stored three times.
   */
  categoryId?: string | null;
  /**
   * Company that owns `categoryId`, so the form can load the right tree to render the saved path.
   *
   * Required because `/api/products` carries no company segment: without it a product classified under
   * company A could not resolve its own path while the screen's company context pointed at B.
   */
  categoryCompanyId?: string | null;
}

/** The form value the edit page consumes: the domain model minus the two server-owned keys. */
export type ProductFormValue = Omit<Product, 'createdAt' | 'updatedAt'>;

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface ProductRequest {
  sku: string;
  name: string;
  shortName?: string;
  satCode?: string;
  productType?: string;
  attributes?: Record<string, unknown>;
  /**
   * The deepest selected node, or `null` to leave the product unclassified.
   *
   * The company is deliberately absent: it is the category's own, and sending one would be a second
   * source of truth the server does not read.
   */
  categoryId?: string | null;
}

export interface ProductControl {
  id: FormControl<string>;
  sku: FormControl<string>;
  name: FormControl<string>;
  shortName: FormControl<string | null>;
  satCode: FormControl<string | null>;
  productType: FormControl<string | null>;
  attributes: FormControl<string | null>;
  enabled: FormControl<boolean>;
  /**
   * The persisted classification: the deepest selected node, `''` when the product has none.
   *
   * Kept beside the three selects on purpose. They are the editing surface and can only be filled once
   * the company's tree arrives, so making them the only source would mean that a tree that fails to
   * load silently drops the saved classification on the next save. This control survives that: it is
   * seeded from the product and rewritten on every select change.
   */
  categoryId: FormControl<string>;
  /** Level 1 of the tree; `''` until a company is chosen and a category is picked. */
  categoryRootId: FormControl<string>;
  /** Level 2; only meaningful with a `categoryRootId`, and cleared when that changes (S7). */
  subcategory1Id: FormControl<string>;
  /** Level 3; only meaningful with a `subcategory1Id`, and cleared when that changes (S7). */
  subcategory2Id: FormControl<string>;
}
