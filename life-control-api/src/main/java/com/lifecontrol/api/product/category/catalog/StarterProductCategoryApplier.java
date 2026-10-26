package com.lifecontrol.api.product.category.catalog;

import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link StarterProductCategoryCatalog} to one company: the same catalog
 * {@code V35__product_category_starter_catalog.sql} seeds for the companies that already existed when
 * the migration ran.
 *
 * <p><b>This class writes through {@link ProductCategoryRepository} and NOT through
 * {@link com.lifecontrol.api.product.category.service.ProductCategoryService}</b>, deliberately. Every
 * public method of that service starts with {@code CurrentUserContext.verifyCompanyAccess}, an
 * operator-context check that a system hook has no business passing: the caller here is a company
 * creation, which may run with no authenticated operator at all. Reusing the service would mean either
 * bypassing the check it exists to enforce or inventing a context that is not the caller's.</p>
 *
 * <p>Holding the rules by construction is what makes the plain insert safe. The catalog is exactly two
 * levels deep, its sibling names are unique by test, and a category is created enabled or disabled as
 * the catalog says — so there is no depth to validate, no cycle to detect and no cascade to run, which
 * is all that the service adds on top of persistence. The one rule the database would reject is a
 * duplicate sibling name ({@code uq_product_categories_sibling_name}, an expression index over
 * {@code lower(category_name)} ignoring parent-less roots), so the guard here compares the same way:
 * case-insensitively, roots against roots and children against the siblings under their own parent.</p>
 *
 * <p>The guard is also the idempotency contract: <b>a second run inserts nothing and returns 0</b>.
 * Roots are inserted first because a child's row needs its parent's id, and an existing root is reused
 * as the parent of the children the company is still missing — a company that had built
 * "Ferretería" by hand keeps that row and receives only the branches it lacks.</p>
 *
 * <p>A child inherits the disabled state of the root it hangs from. Reusing a root the company had
 * already disabled and then inserting an enabled child under it would produce exactly the tree the
 * service's cascade exists to prevent: an enabled node whose ancestor is disabled, unreachable through
 * the tree and still offered as a valid classification. The catalog itself never declares that
 * combination (a test asserts it), but the row the applier <em>reuses</em> is the company's, not the
 * catalog's, so the composition happens at write time.</p>
 *
 * <p>{@code REQUIRES_NEW}, because the only production caller is an
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: at that point the company's transaction has already
 * committed and its connection is still bound to the thread, so a {@code REQUIRED} write would join a
 * completed transaction and never be committed. Suspending it and opening a fresh one is what makes the
 * seeded rows visible.</p>
 */
@Service
public class StarterProductCategoryApplier {

    private final CompanyRepository companyRepository;
    private final ProductCategoryRepository productCategoryRepository;

    public StarterProductCategoryApplier(
            CompanyRepository companyRepository, ProductCategoryRepository productCategoryRepository) {
        this.companyRepository = companyRepository;
        this.productCategoryRepository = productCategoryRepository;
    }

    /**
     * Inserts the catalog rows the company does not have yet and returns how many were inserted.
     *
     * @throws CompanyNotFoundException when the given id is not a company
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int applyTo(UUID companyId) {
        var company = companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));

        var existing = productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId);

        // Sibling name indexes, keyed the way the database compares them: lower-cased, and split by
        // level so a root never collides with a child of another branch.
        Map<String, ProductCategory> rootsByName = new HashMap<>();
        Map<UUID, Set<String>> childNamesByParentId = new HashMap<>();
        for (var category : existing) {
            var name = normalize(category.getCategoryName());
            if (category.getParentCategory() == null) {
                rootsByName.put(name, category);
            } else {
                childNamesByParentId
                        .computeIfAbsent(category.getParentCategory().getId(), parentId -> new HashSet<>())
                        .add(name);
            }
        }

        var inserted = 0;
        for (var entry : StarterProductCategoryCatalog.roots()) {
            var name = normalize(entry.name());
            if (rootsByName.containsKey(name)) {
                continue;
            }
            rootsByName.put(name, productCategoryRepository.save(newCategory(company, null, entry, entry.enabled())));
            inserted++;
        }

        for (var entry : StarterProductCategoryCatalog.children()) {
            var parent = rootsByName.get(normalize(entry.parentName()));
            if (parent == null) {
                // The catalog names a root that is not here; nothing to hang the child from. The parity
                // and shape tests make this unreachable, so it stays a skip rather than an exception.
                continue;
            }
            var name = normalize(entry.name());
            var siblingNames = childNamesByParentId.computeIfAbsent(parent.getId(), parentId -> new HashSet<>());
            if (siblingNames.contains(name)) {
                continue;
            }
            productCategoryRepository.save(
                    newCategory(company, parent, entry, entry.enabled() && Boolean.TRUE.equals(parent.getEnabled())));
            siblingNames.add(name);
            inserted++;
        }

        return inserted;
    }

    private static ProductCategory newCategory(
            Company company, ProductCategory parent, StarterProductCategoryCatalog.Entry entry, boolean enabled) {
        return ProductCategory.builder()
                .company(company)
                .parentCategory(parent)
                .categoryName(entry.name())
                .description(entry.description())
                .displayOrder(entry.displayOrder())
                .satCode(entry.satCode())
                .enabled(enabled)
                .build();
    }

    /** The comparison the sibling-name index uses, applied the same way on both sides. */
    private static String normalize(String categoryName) {
        return categoryName.toLowerCase(Locale.ROOT);
    }
}
