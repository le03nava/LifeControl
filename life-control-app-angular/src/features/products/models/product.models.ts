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
   * Company that **owns this product**, which is also the scope it is addressed through.
   *
   * It is the product's own owner and not the screen's context: the products area keeps its company in
   * `?companyId=` and this field is what proves the row belongs there. The `categoryCompanyId` this
   * replaces existed only because `/api/products` carried no company segment, so a product could not
   * resolve its own category path while the context pointed at another company. The write path now
   * refuses a category of another company, so the two companies can never disagree, and the screen's
   * company arrives from the URL instead of from the response: a second company field on the row would
   * be a second source of truth for the same fact.
   */
  companyId: string;
}

/**
 * The form value the edit page consumes: the domain model minus the server-owned keys.
 *
 * `companyId` is omitted along with the timestamps because it is not an editing surface: it is the
 * screen's `?companyId=` and the scope every write is sent under, so a form value that carried it could
 * only ever restate it — or, worse, contradict it.
 */
export type ProductFormValue = Omit<Product, 'createdAt' | 'updatedAt' | 'companyId'>;

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

// ─── Attribute catalog (S4-S8) ───────────────────────────────────────────────

/**
 * Which entity an attribute belongs to (S7).
 *
 * A shoe's `material_externo` is a fact of the product while its `color` is a fact of the variant —
 * two colours are two barcodes, two stock rows and two prices — so one profile serves both screens and
 * each screen renders only its own level.
 */
export type ProductAttributeLevel = 'PRODUCT' | 'VARIANT';

/**
 * The shape of a value a definition accepts, i.e. which control renders it (S5).
 *
 * Closed on purpose: it mirrors the `ck_product_attribute_definitions_kind` check constraint of the
 * catalog migration, so an unexpected value is a compile error rather than a guessed control.
 */
export type ProductAttributeKind = 'TEXT' | 'ENUM' | 'MULTI_ENUM' | 'MEASUREMENT' | 'BOOLEAN';

/** One allowed value of an enumerable attribute: `code` is what the product JSON stores, `label` is read. */
export interface ProductAttributeValue {
  code: string;
  label: string;
}

/**
 * One attribute a form has to render, with everything the control needs and nothing it has to infer.
 *
 * `values` is empty for every kind without an allowed set. `unitLabel` is the hint printed beside a
 * measurement input and **not** a constraint on the stored unit: the same branch legitimately sells
 * "1/2 pulgada" and "10 milímetros", so the operator may write a different one.
 */
export interface ProductAttributeDefinition {
  code: string;
  label: string;
  level: ProductAttributeLevel;
  kind: ProductAttributeKind;
  unitLabel: string | null;
  required: boolean;
  values: ProductAttributeValue[];
}

/**
 * The attribute profile that resolves for one product category.
 *
 * Mirrors `ProductAttributeProfileResponse`. It is partitioned by level by the API rather than
 * filtered by the client: the split is a fact of the catalog, not a presentation choice, and rendering
 * a variant-level `color` on the product form would suggest a product can have one colour.
 */
export interface ProductAttributeProfile {
  profileKey: string;
  label: string;
  /** The category root the attachment was found under, so a screen can name the branch. */
  resolvedRootName: string;
  productAttributes: ProductAttributeDefinition[];
  variantAttributes: ProductAttributeDefinition[];
}

/**
 * Stored shape of a `MEASUREMENT` value: `{ "value": 1.5, "unit": "cm" }`.
 *
 * One object and not a string because a string is uncomparable and unconvertible; the unit travels
 * with the number because the definition's `unitLabel` is only the hint.
 */
export interface ProductAttributeMeasurement {
  value: number;
  unit: string;
}
