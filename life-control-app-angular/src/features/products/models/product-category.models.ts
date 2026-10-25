import { FormControl } from '@angular/forms';

/**
 * Read model of one node of a company's product category tree.
 *
 * Mirrors `ProductCategoryResponse`: `parentCategoryId` is `null` for a root and `depth` is the
 * node's level counted from 1, so the screen never recomputes the hierarchy it was just given. The
 * list endpoint answers depth-first — a parent immediately precedes its children — which is what
 * makes the flat array renderable as a tree with an indent.
 */
export interface ProductCategory {
  id: string;
  companyId: string;
  parentCategoryId: string | null;
  categoryName: string;
  description: string | null;
  displayOrder: number;
  depth: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Write payload for create and update, mirroring `ProductCategoryRequest`.
 *
 * One shape covers both operations: this catalog has no `version` column and therefore no
 * precondition to carry. `enabled` is **required** here even though the server defaults it to
 * `true` — an update that omitted it would re-enable a disabled category *and* cascade that to its
 * subtree, so the caller always states the flag it means (record F4).
 */
export interface ProductCategoryRequest {
  parentCategoryId: string | null;
  categoryName: string;
  description: string | null;
  displayOrder: number;
  enabled: boolean;
}

/** Typed controls of the category form; `parentCategoryId` is the empty string for a root. */
export interface ProductCategoryControl {
  categoryName: FormControl<string>;
  parentCategoryId: FormControl<string>;
  description: FormControl<string>;
  displayOrder: FormControl<number>;
}

/** Deepest legal level of the tree (backend decision D2: root = 1). */
export const MAX_CATEGORY_DEPTH = 3;
