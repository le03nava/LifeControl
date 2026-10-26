package com.lifecontrol.api.product.category.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.category.dto.ProductCategoryRequest;
import com.lifecontrol.api.product.category.dto.ProductCategoryResponse;
import com.lifecontrol.api.product.category.exception.DisabledParentCategoryException;
import com.lifecontrol.api.product.category.exception.DuplicateProductCategoryException;
import com.lifecontrol.api.product.category.exception.ProductCategoryDepthExceededException;
import com.lifecontrol.api.product.category.exception.ProductCategoryHierarchyCycleException;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the company-scoped product category tree.
 *
 * <p>Mirrors {@link com.lifecontrol.api.hr.service.DepartmentService}, the repository's company-scoped
 * catalog: every public method starts by resolving the company through {@link #resolveCompany(UUID)},
 * whose first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)}, and every single-row
 * lookup is {@code companyId}-scoped, so a category of another company is a
 * {@link ProductCategoryNotFoundException} rather than a 200.</p>
 *
 * <p>This class is where three of the tree's rules live, because the database cannot express them:
 * the {@value #MAX_DEPTH}-level ceiling (a depth {@code CHECK} would need a trigger), the deep
 * cycle that a self-referencing foreign key cannot forbid, and the cascade that disables a node's
 * whole subtree. The fourth rule — a parent in the same company — is enforced by the composite
 * foreign key in {@code V31}, so this class validates it for the error message and the engine
 * guarantees it regardless.</p>
 *
 * <p>Reads are <b>not</b> cached, deliberately: a company-scoped read must run its scope check on
 * every call, and a {@code @Cacheable} would serve a cache hit without ever reaching
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the argument
 * {@code DepartmentService} records, and the reason the neighbouring {@code CompanyRegionService}
 * stopped caching its scoped reads.</p>
 *
 * <p>The list is one statement plus an in-memory depth-first walk. That is not a shortcut: the ceiling
 * bounds the tree, so the alternative — a recursive CTE or one query per level — would add a second
 * source of truth about ordering for no gain. The walk sorts by the order the query already returned
 * ({@code displayOrder}, then name) instead of re-sorting in Java, so the database collation decides
 * name ordering exactly once.</p>
 */
@Service
public class ProductCategoryService {

    /**
     * Deepest legal level: {@code categoria} (1) &rarr; {@code subcategoria} (2) &rarr;
     * {@code sub-subcategoria} (3). Decision D2 of {@code odd/tasks/product-categories-backend.md}.
     */
    public static final int MAX_DEPTH = 3;

    private static final Logger logger = LoggerFactory.getLogger(ProductCategoryService.class);

    private final ProductCategoryRepository productCategoryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public ProductCategoryService(
            ProductCategoryRepository productCategoryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.productCategoryRepository = productCategoryRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded, which is the contract
     * {@link com.lifecontrol.api.hr.service.DepartmentService} established for company-scoped
     * catalogs (record E12).</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /**
     * Returns the company's tree as a flat, depth-first ordered list: a parent is immediately followed
     * by its children, and each entry carries its {@code depth} and {@code parentCategoryId}, so the
     * client renders the hierarchy without rebuilding it.
     *
     * <p>When {@code includeDisabled} is false a node is returned only if it <b>and every ancestor</b>
     * are enabled. Filtering by the node's own flag would promote a child of a disabled parent to a
     * root of the visible list and report a depth it does not have — the cascade rules make that state
     * unreachable through this API, but the read must not depend on every writer having obeyed them.</p>
     */
    @Transactional(readOnly = true)
    public List<ProductCategoryResponse> getCategories(UUID companyId, boolean includeDisabled) {
        resolveCompany(companyId);

        var rows = productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId);
        return toDepthFirstList(rows, includeDisabled);
    }

    @Transactional(readOnly = true)
    public ProductCategoryResponse getCategoryById(UUID companyId, UUID id) {
        resolveCompany(companyId);

        var category = productCategoryRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new ProductCategoryNotFoundException(id));

        return toResponse(category, depthOf(category, companyId));
    }

    @Transactional
    public ProductCategoryResponse createCategory(UUID companyId, ProductCategoryRequest request) {
        var company = resolveCompany(companyId);

        var parent = resolveParent(companyId, request.parentCategoryId());
        var depth = parent == null ? 1 : depthOf(parent, companyId) + 1;
        assertWithinCeiling(depth);
        assertParentEnabled(parent, companyId);
        assertSiblingNameFree(companyId, request.parentCategoryId(), request.categoryName(), null);

        var category = ProductCategory.builder()
                .company(company)
                .parentCategory(parent)
                .categoryName(request.categoryName())
                .description(request.description())
                .satCode(request.satCode())
                .displayOrder(request.displayOrder())
                .enabled(request.enabled())
                .build();

        var saved = productCategoryRepository.save(category);
        logger.info(
                "Product category created: id={}, companyId={}, parentId={}, depth={}",
                saved.getId(),
                companyId,
                request.parentCategoryId(),
                depth);

        return toResponse(saved, depth);
    }

    /**
     * Updates a category, including moving it to another parent (or to the root).
     *
     * <p>A move re-runs every rule the position was subject to: the new parent must be in the same
     * company and enabled, must not be the category itself or one of its descendants, and the
     * resulting level must stay within the ceiling. The name uniqueness check excludes the row being
     * updated, so re-saving a category under the same name is not a collision.</p>
     *
     * <p>Two rules are easier to miss than they look, and both are the reason this method is not a
     * field copy:</p>
     *
     * <ul>
     *   <li>The ceiling applies to the moved node's <b>subtree</b>, not only to the node. Checking
     *       {@code depthOf(parent) + 1} alone lets a level-2 node that carries a level-3 child move
     *       under another level-2 node: the node lands legally at level 3 and its child silently
     *       becomes level 4, which the list then drops as unreachable. The move therefore measures
     *       the deepest descendant it drags along, and the check runs only when the parent actually
     *       changes — a rename cannot deepen anything.</li>
     *   <li>{@code enabled = false} in the payload has to cascade exactly like
     *       {@code PATCH /{id}/enable} and {@code DELETE} do. Without it, {@code PUT} would be a
     *       second, quieter way to disable a node and leave an enabled child under a disabled parent
     *       — the state R6 exists to prevent, and one the read would hide from the list while
     *       {@code GET /{id}} still returned it.</li>
     * </ul>
     */
    @Transactional
    public ProductCategoryResponse updateCategory(UUID companyId, UUID id, ProductCategoryRequest request) {
        resolveCompany(companyId);

        var category = productCategoryRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new ProductCategoryNotFoundException(id));

        var parent = resolveParent(companyId, request.parentCategoryId());
        assertNotOwnAncestor(category, parent, companyId);
        var depth = parent == null ? 1 : depthOf(parent, companyId) + 1;
        assertWithinCeiling(depth);
        if (parentChanged(category, parent)) {
            assertWithinCeiling(depth + subtreeHeightBelow(category, companyId));
        }
        assertParentEnabled(parent, companyId);
        assertSiblingNameFree(companyId, request.parentCategoryId(), request.categoryName(), id);

        category.setParentCategory(parent);
        category.setCategoryName(request.categoryName());
        category.setDescription(request.description());
        category.setSatCode(request.satCode());
        category.setDisplayOrder(request.displayOrder());
        category.setEnabled(request.enabled());

        var updated = productCategoryRepository.save(category);

        if (Boolean.FALSE.equals(updated.getEnabled())) {
            var subtree = subtreeOf(updated, companyId);
            subtree.forEach(node -> node.setEnabled(false));
            productCategoryRepository.saveAll(subtree);
        }

        logger.info(
                "Product category updated: id={}, companyId={}, parentId={}, depth={}",
                updated.getId(),
                companyId,
                request.parentCategoryId(),
                depth);

        return toResponse(updated, depth);
    }

    /**
     * Soft-deletes a category and, through {@link #setCategoryEnabled}, its whole subtree.
     *
     * <p>There is no hard delete: the row is kept so the history stays readable, the same contract
     * every catalog in this schema holds.</p>
     */
    @Transactional
    public void deleteCategory(UUID companyId, UUID id) {
        setCategoryEnabled(companyId, id, false);
    }

    /**
     * Enables or disables a category.
     *
     * <p>Disabling cascades to the subtree: a child left enabled under a disabled parent would be
     * unreachable through the tree and yet still offered as a valid classification, so the cascade is
     * the only state that keeps the read honest. Enabling requires the whole ancestor chain to be
     * enabled already, so a branch can only ever be activated from the root down.</p>
     */
    @Transactional
    public ProductCategoryResponse setCategoryEnabled(UUID companyId, UUID id, boolean enabled) {
        resolveCompany(companyId);

        var category = productCategoryRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new ProductCategoryNotFoundException(id));

        if (enabled) {
            assertParentEnabled(category.getParentCategory(), companyId);
            category.setEnabled(true);
            var saved = productCategoryRepository.save(category);
            logger.info("Product category enabled: id={}, companyId={}", id, companyId);
            return toResponse(saved, depthOf(saved, companyId));
        }

        var subtree = subtreeOf(category, companyId);
        subtree.forEach(node -> node.setEnabled(false));
        productCategoryRepository.saveAll(subtree);

        logger.info("Product category disabled: id={}, companyId={}, affectedNodes={}", id, companyId, subtree.size());

        category.setEnabled(false);
        return toResponse(category, depthOf(category, companyId));
    }

    /**
     * Resolves a requested parent id, or {@code null} for a root.
     *
     * <p>The lookup is company-scoped on purpose: a parent id belonging to another company is a
     * {@link ProductCategoryNotFoundException}, not a validation error, exactly as every other
     * company-scoped lookup behaves — and the composite foreign key in {@code V31} would refuse the
     * write even if this check were removed.</p>
     */
    private ProductCategory resolveParent(UUID companyId, UUID parentCategoryId) {
        if (parentCategoryId == null) {
            return null;
        }
        return productCategoryRepository
                .findByCompanyIdAndId(companyId, parentCategoryId)
                .orElseThrow(() -> new ProductCategoryNotFoundException(parentCategoryId));
    }

    /**
     * Counts the node's level, 1 for a root, by walking its ancestors with company-scoped lookups.
     *
     * <p>Bounded by the ceiling plus one: a stored chain longer than {@link #MAX_DEPTH}, or one that
     * loops, means the data predates the rule it violates, and the returned count is then large enough
     * for the ceiling check to refuse the operation instead of trusting the chain.</p>
     */
    private int depthOf(ProductCategory category, UUID companyId) {
        var depth = 1;
        var visited = new HashSet<UUID>();
        visited.add(category.getId());

        var current = category;
        while (current.getParentCategory() != null) {
            var parentId = current.getParentCategory().getId();
            if (!visited.add(parentId)) {
                // A stored cycle is not a level: report it as deeper than the ceiling so every write
                // refuses until the row is repaired, instead of writing on top of corrupt data.
                return MAX_DEPTH + 1;
            }
            current = productCategoryRepository
                    .findByCompanyIdAndId(companyId, parentId)
                    .orElseThrow(() -> new ProductCategoryNotFoundException(parentId));
            depth++;
        }
        return depth;
    }

    /**
     * Whether the update moves the category to a different parent (or to/from the root).
     *
     * <p>A rename keeps its parent, and the subtree it carries was already inside the ceiling when the
     * node got there, so measuring it again would cost a query to re-learn a fact that cannot have
     * changed.</p>
     */
    private boolean parentChanged(ProductCategory category, ProductCategory newParent) {
        var previous = category.getParentCategory();
        return !Objects.equals(
                previous == null ? null : previous.getId(), newParent == null ? null : newParent.getId());
    }

    /**
     * How many levels sit <b>below</b> the node, 0 for a leaf, measured in the company's current tree.
     *
     * <p>This is the half of the ceiling check that a move can silently break: the node's own level is
     * legal while the descendants it drags along are not. Measured breadth-first over one query, with
     * a visited set so a stored cycle terminates instead of looping — the same reason
     * {@link #subtreeOf} carries one.</p>
     */
    private int subtreeHeightBelow(ProductCategory category, UUID companyId) {
        var rows = productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId);
        var childrenByParent = childrenByParent(rows);

        var visited = new HashSet<UUID>();
        visited.add(category.getId());
        var frontier = List.of(category);
        var height = 0;

        while (!frontier.isEmpty()) {
            var next = new ArrayList<ProductCategory>();
            for (var node : frontier) {
                for (var child : childrenByParent.getOrDefault(node.getId(), List.of())) {
                    if (visited.add(child.getId())) {
                        next.add(child);
                    }
                }
            }
            if (!next.isEmpty()) {
                height++;
            }
            frontier = next;
        }
        return height;
    }

    /**
     * Refuses a node whose level would exceed {@link #MAX_DEPTH}.
     *
     * @throws ProductCategoryDepthExceededException when {@code attemptedDepth} is past the ceiling
     */
    private void assertWithinCeiling(int attemptedDepth) {
        if (attemptedDepth > MAX_DEPTH) {
            throw new ProductCategoryDepthExceededException(attemptedDepth, MAX_DEPTH);
        }
    }

    /**
     * Refuses a parent that is soft-deleted, or that hangs from a soft-deleted ancestor.
     *
     * @throws DisabledParentCategoryException naming the first disabled node in the chain
     */
    private void assertParentEnabled(ProductCategory parent, UUID companyId) {
        var current = parent;
        var visited = new HashSet<UUID>();
        while (current != null) {
            if (!Boolean.TRUE.equals(current.getEnabled())) {
                throw new DisabledParentCategoryException(
                        "Cannot enable this product category: category with id " + current.getId() + " is disabled");
            }
            if (!visited.add(current.getId())) {
                throw new DisabledParentCategoryException(
                        "Cannot enable this product category: its parent chain contains a cycle at id "
                                + current.getId());
            }
            var parentId = current.getParentCategory() == null
                    ? null
                    : current.getParentCategory().getId();
            current = parentId == null
                    ? null
                    : productCategoryRepository
                            .findByCompanyIdAndId(companyId, parentId)
                            .orElseThrow(() -> new ProductCategoryNotFoundException(parentId));
        }
    }

    /**
     * Refuses moving a category under itself or under one of its own descendants.
     *
     * <p>The database closes only the one-node case, so this walk is the only guard against a cycle
     * that would make every depth-first read loop forever.</p>
     */
    private void assertNotOwnAncestor(ProductCategory category, ProductCategory newParent, UUID companyId) {
        if (newParent == null) {
            return;
        }
        if (newParent.getId().equals(category.getId())) {
            throw ProductCategoryHierarchyCycleException.selfParent();
        }

        var current = newParent;
        var visited = new HashSet<UUID>();
        while (current != null) {
            if (current.getId().equals(category.getId())) {
                throw ProductCategoryHierarchyCycleException.descendantParent();
            }
            if (!visited.add(current.getId())) {
                throw ProductCategoryHierarchyCycleException.descendantParent();
            }
            var parentId = current.getParentCategory() == null
                    ? null
                    : current.getParentCategory().getId();
            current = parentId == null
                    ? null
                    : productCategoryRepository
                            .findByCompanyIdAndId(companyId, parentId)
                            .orElseThrow(() -> new ProductCategoryNotFoundException(parentId));
        }
    }

    /**
     * Refuses a name already used by a sibling, roots included.
     *
     * <p>Two queries and not one: a root has no parent, and passing {@code null} to a
     * {@code parentCategoryId = ?} comparison matches nothing, so the root case would silently pass the
     * check and only the database index would catch it — a 500 where a 409 belongs.</p>
     */
    private void assertSiblingNameFree(UUID companyId, UUID parentCategoryId, String categoryName, UUID excludeId) {
        var taken = parentCategoryId == null
                ? (excludeId == null
                        ? productCategoryRepository.existsByCompanyIdAndParentCategoryIsNullAndCategoryNameIgnoreCase(
                                companyId, categoryName)
                        : productCategoryRepository
                                .existsByCompanyIdAndParentCategoryIsNullAndCategoryNameIgnoreCaseAndIdNot(
                                        companyId, categoryName, excludeId))
                : (excludeId == null
                        ? productCategoryRepository.existsByCompanyIdAndParentCategoryIdAndCategoryNameIgnoreCase(
                                companyId, parentCategoryId, categoryName)
                        : productCategoryRepository
                                .existsByCompanyIdAndParentCategoryIdAndCategoryNameIgnoreCaseAndIdNot(
                                        companyId, parentCategoryId, categoryName, excludeId));

        if (taken) {
            throw new DuplicateProductCategoryException(categoryName, parentCategoryId);
        }
    }

    /** Collects the node and all of its descendants, in the company, using one query. */
    private List<ProductCategory> subtreeOf(ProductCategory category, UUID companyId) {
        var rows = productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId);
        var childrenByParent = childrenByParent(rows);

        var subtree = new ArrayList<ProductCategory>();
        var pending = new ArrayList<ProductCategory>();
        pending.add(category);
        var visited = new HashSet<UUID>();

        while (!pending.isEmpty()) {
            var current = pending.remove(pending.size() - 1);
            if (!visited.add(current.getId())) {
                continue;
            }
            subtree.add(current);
            pending.addAll(childrenByParent.getOrDefault(current.getId(), List.of()));
        }
        return subtree;
    }

    private Map<UUID, List<ProductCategory>> childrenByParent(List<ProductCategory> rows) {
        var childrenByParent = new HashMap<UUID, List<ProductCategory>>();
        for (var row : rows) {
            var parent = row.getParentCategory();
            if (parent != null) {
                childrenByParent
                        .computeIfAbsent(parent.getId(), key -> new ArrayList<>())
                        .add(row);
            }
        }
        return childrenByParent;
    }

    private List<ProductCategoryResponse> toDepthFirstList(List<ProductCategory> rows, boolean includeDisabled) {
        var byId = new LinkedHashMap<UUID, ProductCategory>();
        rows.forEach(row -> byId.put(row.getId(), row));

        var visible = new ArrayList<ProductCategory>();
        for (var row : rows) {
            if (includeDisabled || isVisibleInTree(row, byId)) {
                visible.add(row);
            }
        }

        var childrenByParent = childrenByParent(visible);
        var responses = new ArrayList<ProductCategoryResponse>(visible.size());
        var visited = new HashSet<UUID>();

        for (var row : visible) {
            if (row.getParentCategory() == null) {
                appendDepthFirst(row, 1, childrenByParent, visited, responses);
            }
        }

        // A node whose parent is missing from the visible set is unreachable from any root; the
        // ancestor rule above makes that state unreachable, and dropping it is the safe answer.
        for (var row : visible) {
            if (!visited.contains(row.getId())) {
                logger.warn(
                        "Product category is not reachable from a root and was omitted from the list: id={}",
                        row.getId());
            }
        }
        return responses;
    }

    /**
     * Whether every node from {@code row} up to its root is enabled.
     *
     * <p>The walk is bounded by {@link #MAX_DEPTH} plus one and remembers what it visited, so a stored
     * cycle or a chain deeper than the ceiling is reported as <b>not</b> visible instead of looping.
     * </p>
     */
    private boolean isVisibleInTree(ProductCategory row, Map<UUID, ProductCategory> byId) {
        var visited = new HashSet<UUID>();
        var current = row;
        var levels = 1;

        while (current != null) {
            if (!visited.add(current.getId()) || levels > MAX_DEPTH) {
                return false;
            }
            if (!Boolean.TRUE.equals(current.getEnabled())) {
                return false;
            }
            var parent = current.getParentCategory();
            current = parent == null ? null : byId.get(parent.getId());
            levels++;
        }
        return true;
    }

    private void appendDepthFirst(
            ProductCategory category,
            int depth,
            Map<UUID, List<ProductCategory>> childrenByParent,
            Set<UUID> visited,
            List<ProductCategoryResponse> out) {
        if (!visited.add(category.getId())) {
            return;
        }
        out.add(toResponse(category, depth));
        for (var child : childrenByParent.getOrDefault(category.getId(), List.of())) {
            appendDepthFirst(child, depth + 1, childrenByParent, visited, out);
        }
    }

    private ProductCategoryResponse toResponse(ProductCategory category, int depth) {
        var parent = category.getParentCategory();
        return new ProductCategoryResponse(
                category.getId(),
                category.getCompany().getId(),
                parent == null ? null : parent.getId(),
                category.getCategoryName(),
                category.getDescription(),
                category.getSatCode(),
                category.getDisplayOrder(),
                depth,
                category.getEnabled(),
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}
