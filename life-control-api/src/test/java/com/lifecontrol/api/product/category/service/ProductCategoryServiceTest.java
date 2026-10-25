package com.lifecontrol.api.product.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit verification of the seven rules the category tree carries, one nested class per operation.
 *
 * <p>Two of these rules are only enforced here — the three-level ceiling and the deep-cycle guard —
 * because PostgreSQL cannot express them (a depth CHECK would need a trigger and a self-referencing
 * foreign key cannot say "no ancestor of mine"), so their tests are the rule's only pin. The tenancy
 * rule is enforced twice: the composite foreign key is proven against real PostgreSQL by
 * {@code ProductCategorySchemaMigrationIntegrationTest}, and the company-scoped lookups that turn
 * another company's category into a 404 are proven here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCategoryService Tests")
class ProductCategoryServiceTest {

    @Mock
    private ProductCategoryRepository productCategoryRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private ProductCategoryService productCategoryService;

    private UUID companyId;
    private Company company;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        company = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
    }

    private ProductCategory category(UUID id, String name, ProductCategory parent, boolean enabled, int displayOrder) {
        return ProductCategory.builder()
                .id(id)
                .company(company)
                .parentCategory(parent)
                .categoryName(name)
                .displayOrder(displayOrder)
                .enabled(enabled)
                .build();
    }

    private void stubLookup(ProductCategory category) {
        when(productCategoryRepository.findByCompanyIdAndId(companyId, category.getId()))
                .thenReturn(Optional.of(category));
    }

    private void stubSaveReturnsArgument() {
        when(productCategoryRepository.save(any(ProductCategory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ─── Reads ────────────────────────────────────────────────────

    @Nested
    @DisplayName("reads")
    class ReadTests {

        @Test
        @DisplayName("returns the tree depth-first, each node carrying its depth and parent id")
        void returnsDepthFirstListWithDepthAndParent() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var rootB = category(UUID.randomUUID(), "Limpieza", null, true, 2);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            var grandchild = category(UUID.randomUUID(), "Colas", child, true, 1);

            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(root, rootB, child, grandchild));

            var result = productCategoryService.getCategories(companyId, false);

            assertThat(result)
                    .extracting(ProductCategoryResponse::categoryName)
                    .containsExactly("Bebidas", "Gaseosas", "Colas", "Limpieza");
            assertThat(result).extracting(ProductCategoryResponse::depth).containsExactly(1, 2, 3, 1);
            assertThat(result.get(1).parentCategoryId()).isEqualTo(root.getId());
            assertThat(result.get(0).parentCategoryId()).isNull();
        }

        @Test
        @DisplayName("excludes a disabled node and everything below it, not only the node itself")
        void excludesDisabledSubtree() {
            var disabledRoot = category(UUID.randomUUID(), "Bebidas", null, false, 1);
            var stillEnabledChild = category(UUID.randomUUID(), "Gaseosas", disabledRoot, true, 1);
            var root = category(UUID.randomUUID(), "Limpieza", null, true, 2);

            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(disabledRoot, stillEnabledChild, root));

            var result = productCategoryService.getCategories(companyId, false);

            assertThat(result).extracting(ProductCategoryResponse::categoryName).containsExactly("Limpieza");
        }

        @Test
        @DisplayName("keeps disabled nodes when includeDisabled is true")
        void keepsDisabledWhenRequested() {
            var disabledRoot = category(UUID.randomUUID(), "Bebidas", null, false, 1);

            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(disabledRoot));

            var result = productCategoryService.getCategories(companyId, true);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).enabled()).isFalse();
        }

        @Test
        @DisplayName("verifies company access before the company is even loaded")
        void verifiesAccessBeforeLoadingTheCompany() {
            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of());

            productCategoryService.getCategories(companyId, false);

            var order = inOrder(currentUserContext, companyRepository);
            order.verify(currentUserContext).verifyCompanyAccess(companyId);
            order.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("reports a missing company as 404 after the access check")
        void missingCompanyIsNotFound() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productCategoryService.getCategories(companyId, false))
                    .isInstanceOf(CompanyNotFoundException.class);
        }

        @Test
        @DisplayName("computes the depth of a single category from its ancestor chain")
        void singleReadComputesDepth() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            var grandchild = category(UUID.randomUUID(), "Colas", child, true, 1);

            stubLookup(grandchild);
            stubLookup(child);
            stubLookup(root);

            var result = productCategoryService.getCategoryById(companyId, grandchild.getId());

            assertThat(result.depth()).isEqualTo(3);
            assertThat(result.parentCategoryId()).isEqualTo(child.getId());
        }

        @Test
        @DisplayName("reports a category of another company as 404, never as a row")
        void anotherCompanysCategoryIsNotFound() {
            var otherCompanyCategoryId = UUID.randomUUID();
            when(productCategoryRepository.findByCompanyIdAndId(companyId, otherCompanyCategoryId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> productCategoryService.getCategoryById(companyId, otherCompanyCategoryId))
                    .isInstanceOf(ProductCategoryNotFoundException.class);
        }
    }

    // ─── Create ───────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("creates a root at level 1 when no parent is given")
        void createsRoot() {
            stubSaveReturnsArgument();
            var request = new ProductCategoryRequest(null, "Bebidas", "Bebidas y refrescos", 1, true);

            var result = productCategoryService.createCategory(companyId, request);

            assertThat(result.depth()).isEqualTo(1);
            assertThat(result.parentCategoryId()).isNull();
            assertThat(result.categoryName()).isEqualTo("Bebidas");
            assertThat(result.companyId()).isEqualTo(companyId);
        }

        @Test
        @DisplayName("creates a child of a root at level 2")
        void createsChild() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            stubLookup(root);
            stubSaveReturnsArgument();

            var result = productCategoryService.createCategory(
                    companyId, new ProductCategoryRequest(root.getId(), "Gaseosas", null, 1, true));

            assertThat(result.depth()).isEqualTo(2);
            assertThat(result.parentCategoryId()).isEqualTo(root.getId());
        }

        @Test
        @DisplayName("creates a level-3 category, the deepest the ceiling allows")
        void createsGrandchild() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(child);
            stubLookup(root);
            stubSaveReturnsArgument();

            var result = productCategoryService.createCategory(
                    companyId, new ProductCategoryRequest(child.getId(), "Colas", null, 1, true));

            assertThat(result.depth()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses a level-4 category and writes nothing")
        void refusesFourthLevel() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            var grandchild = category(UUID.randomUUID(), "Colas", child, true, 1);
            stubLookup(grandchild);
            stubLookup(child);
            stubLookup(root);

            assertThatThrownBy(() -> productCategoryService.createCategory(
                            companyId, new ProductCategoryRequest(grandchild.getId(), "Zero", null, 1, true)))
                    .isInstanceOf(ProductCategoryDepthExceededException.class)
                    .hasMessageContaining("3 levels");

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses a child of a disabled parent")
        void refusesChildOfDisabledParent() {
            var root = category(UUID.randomUUID(), "Bebidas", null, false, 1);
            stubLookup(root);

            assertThatThrownBy(() -> productCategoryService.createCategory(
                            companyId, new ProductCategoryRequest(root.getId(), "Gaseosas", null, 1, true)))
                    .isInstanceOf(DisabledParentCategoryException.class);

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses a child whose enabled parent hangs from a disabled grandparent")
        void refusesChildOfAncestorDisabled() {
            var disabledRoot = category(UUID.randomUUID(), "Bebidas", null, false, 1);
            var enabledChild = category(UUID.randomUUID(), "Gaseosas", disabledRoot, true, 1);
            stubLookup(enabledChild);
            stubLookup(disabledRoot);

            assertThatThrownBy(() -> productCategoryService.createCategory(
                            companyId, new ProductCategoryRequest(enabledChild.getId(), "Colas", null, 1, true)))
                    .isInstanceOf(DisabledParentCategoryException.class)
                    .hasMessageContaining(disabledRoot.getId().toString());
        }

        @Test
        @DisplayName("refuses a second root with the same name")
        void refusesDuplicateRoot() {
            when(productCategoryRepository.existsByCompanyIdAndParentCategoryIsNullAndCategoryNameIgnoreCase(
                            companyId, "Bebidas"))
                    .thenReturn(true);

            assertThatThrownBy(() -> productCategoryService.createCategory(
                            companyId, new ProductCategoryRequest(null, "Bebidas", null, 1, true)))
                    .isInstanceOf(DuplicateProductCategoryException.class)
                    .hasMessageContaining("<root>");

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses a sibling with the same name")
        void refusesDuplicateSibling() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            stubLookup(root);
            when(productCategoryRepository.existsByCompanyIdAndParentCategoryIdAndCategoryNameIgnoreCase(
                            companyId, root.getId(), "Gaseosas"))
                    .thenReturn(true);

            assertThatThrownBy(() -> productCategoryService.createCategory(
                            companyId, new ProductCategoryRequest(root.getId(), "Gaseosas", null, 1, true)))
                    .isInstanceOf(DuplicateProductCategoryException.class)
                    .hasMessageContaining(root.getId().toString());

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("allows the same name under a different parent")
        void allowsSameNameUnderAnotherParent() {
            var root = category(UUID.randomUUID(), "Limpieza", null, true, 1);
            stubLookup(root);
            stubSaveReturnsArgument();

            var result = productCategoryService.createCategory(
                    companyId, new ProductCategoryRequest(root.getId(), "Gaseosas", null, 1, true));

            assertThat(result.categoryName()).isEqualTo("Gaseosas");
        }

        @Test
        @DisplayName("reports a parent from another company as 404 and writes nothing")
        void refusesParentFromAnotherCompany() {
            var foreignParentId = UUID.randomUUID();
            when(productCategoryRepository.findByCompanyIdAndId(companyId, foreignParentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> productCategoryService.createCategory(
                            companyId, new ProductCategoryRequest(foreignParentId, "Gaseosas", null, 1, true)))
                    .isInstanceOf(ProductCategoryNotFoundException.class);

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("stores the parent and the company it was resolved in")
        void storesResolvedParentAndCompany() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            stubLookup(root);
            stubSaveReturnsArgument();

            productCategoryService.createCategory(
                    companyId, new ProductCategoryRequest(root.getId(), "Gaseosas", null, 7, true));

            var captor = ArgumentCaptor.forClass(ProductCategory.class);
            verify(productCategoryRepository).save(captor.capture());
            assertThat(captor.getValue().getParentCategory()).isSameAs(root);
            assertThat(captor.getValue().getCompany()).isSameAs(company);
            assertThat(captor.getValue().getDisplayOrder()).isEqualTo(7);
        }
    }

    // ─── Update ───────────────────────────────────────────────────

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("renames in place and keeps the depth")
        void renamesKeepingDepth() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(child);
            stubLookup(root);
            stubSaveReturnsArgument();

            var result = productCategoryService.updateCategory(
                    companyId, child.getId(), new ProductCategoryRequest(root.getId(), "Refrescos", null, 1, true));

            assertThat(result.categoryName()).isEqualTo("Refrescos");
            assertThat(result.depth()).isEqualTo(2);
        }

        @Test
        @DisplayName("moves a category to the root, level 1")
        void movesToRoot() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(child);
            stubSaveReturnsArgument();

            var result = productCategoryService.updateCategory(
                    companyId, child.getId(), new ProductCategoryRequest(null, "Gaseosas", null, 1, true));

            assertThat(result.depth()).isEqualTo(1);
            assertThat(result.parentCategoryId()).isNull();
        }

        @Test
        @DisplayName("refuses a category as its own parent")
        void refusesSelfParent() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            stubLookup(root);

            assertThatThrownBy(() -> productCategoryService.updateCategory(
                            companyId,
                            root.getId(),
                            new ProductCategoryRequest(root.getId(), "Bebidas", null, 1, true)))
                    .isInstanceOf(ProductCategoryHierarchyCycleException.class)
                    .hasMessageContaining("its own parent");

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses moving a category under one of its own descendants")
        void refusesDescendantParent() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(root);
            stubLookup(child);

            assertThatThrownBy(() -> productCategoryService.updateCategory(
                            companyId,
                            root.getId(),
                            new ProductCategoryRequest(child.getId(), "Bebidas", null, 1, true)))
                    .isInstanceOf(ProductCategoryHierarchyCycleException.class)
                    .hasMessageContaining("own descendants");

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses a move that would produce a fourth level")
        void refusesMovePastCeiling() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            var grandchild = category(UUID.randomUUID(), "Colas", child, true, 1);
            var moving = category(UUID.randomUUID(), "Zero", null, true, 2);
            stubLookup(moving);
            stubLookup(grandchild);
            stubLookup(child);
            stubLookup(root);

            assertThatThrownBy(() -> productCategoryService.updateCategory(
                            companyId,
                            moving.getId(),
                            new ProductCategoryRequest(grandchild.getId(), "Zero", null, 1, true)))
                    .isInstanceOf(ProductCategoryDepthExceededException.class);

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses a rename that collides with a sibling, excluding the row itself")
        void refusesDuplicateSiblingExcludingSelf() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(child);
            stubLookup(root);
            when(productCategoryRepository.existsByCompanyIdAndParentCategoryIdAndCategoryNameIgnoreCaseAndIdNot(
                            companyId, root.getId(), "Refrescos", child.getId()))
                    .thenReturn(true);

            assertThatThrownBy(() -> productCategoryService.updateCategory(
                            companyId,
                            child.getId(),
                            new ProductCategoryRequest(root.getId(), "Refrescos", null, 1, true)))
                    .isInstanceOf(DuplicateProductCategoryException.class);
        }

        @Test
        @DisplayName("allows re-saving a category under its own name")
        void allowsKeepingItsOwnName() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(child);
            stubLookup(root);
            stubSaveReturnsArgument();

            var result = productCategoryService.updateCategory(
                    companyId, child.getId(), new ProductCategoryRequest(root.getId(), "Gaseosas", null, 3, true));

            assertThat(result.displayOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses a move under a disabled parent")
        void refusesMoveUnderDisabledParent() {
            var disabledRoot = category(UUID.randomUUID(), "Bebidas", null, false, 1);
            var moving = category(UUID.randomUUID(), "Gaseosas", null, true, 2);
            stubLookup(moving);
            stubLookup(disabledRoot);

            assertThatThrownBy(() -> productCategoryService.updateCategory(
                            companyId,
                            moving.getId(),
                            new ProductCategoryRequest(disabledRoot.getId(), "Gaseosas", null, 1, true)))
                    .isInstanceOf(DisabledParentCategoryException.class);
        }

        @Test
        @DisplayName("refuses a move that would push a descendant past the ceiling and writes nothing")
        void refusesMoveThatPushesDescendantPastCeiling() {
            var rootA = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var nodeB = category(UUID.randomUUID(), "Gaseosas", rootA, true, 1);
            var nodeC = category(UUID.randomUUID(), "Colas", nodeB, true, 1);
            var rootP = category(UUID.randomUUID(), "Limpieza", null, true, 2);
            var nodeQ = category(UUID.randomUUID(), "Detergentes", rootP, true, 1);
            stubLookup(nodeB);
            stubLookup(nodeQ);
            stubLookup(rootP);
            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(rootA, nodeB, nodeC, rootP, nodeQ));

            assertThatThrownBy(() -> productCategoryService.updateCategory(
                            companyId,
                            nodeB.getId(),
                            new ProductCategoryRequest(nodeQ.getId(), "Gaseosas", null, 1, true)))
                    .isInstanceOf(ProductCategoryDepthExceededException.class);

            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
            assertThat(nodeC.getParentCategory()).isSameAs(nodeB);
            assertThat(nodeB.getParentCategory()).isSameAs(rootA);
        }

        @Test
        @DisplayName("an update that disables a node disables its whole subtree")
        void updateDisableCascadesToSubtree() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            var grandchild = category(UUID.randomUUID(), "Colas", child, true, 1);
            var untouched = category(UUID.randomUUID(), "Limpieza", null, true, 2);
            stubLookup(root);
            stubSaveReturnsArgument();
            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(root, child, grandchild, untouched));

            productCategoryService.updateCategory(
                    companyId, root.getId(), new ProductCategoryRequest(null, "Bebidas", null, 1, false));

            assertThat(root.getEnabled()).isFalse();
            assertThat(child.getEnabled()).isFalse();
            assertThat(grandchild.getEnabled()).isFalse();
            assertThat(untouched.getEnabled()).isTrue();
        }
    }

    // ─── Enable / disable ─────────────────────────────────────────

    @Nested
    @DisplayName("enable and disable")
    class EnabledTests {

        @Test
        @DisplayName("disabling a node disables its whole subtree in one write")
        void disablingCascadesToSubtree() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            var grandchild = category(UUID.randomUUID(), "Colas", child, true, 1);
            var untouched = category(UUID.randomUUID(), "Limpieza", null, true, 2);
            stubLookup(root);
            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(root, child, grandchild, untouched));

            productCategoryService.setCategoryEnabled(companyId, root.getId(), false);

            var captor = ArgumentCaptor.forClass(List.class);
            verify(productCategoryRepository).saveAll(captor.capture());
            var saved = (List<?>) captor.getValue();
            assertThat(saved).hasSize(3);
            assertThat(saved)
                    .allSatisfy(node ->
                            assertThat(((ProductCategory) node).getEnabled()).isFalse());
            assertThat(untouched.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("delete is a soft delete that runs the same cascade")
        void deleteIsSoftDeleteWithCascade() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, true, 1);
            stubLookup(root);
            when(productCategoryRepository.findByCompanyIdOrderByDisplayOrderAscCategoryNameAsc(companyId))
                    .thenReturn(List.of(root, child));

            productCategoryService.deleteCategory(companyId, root.getId());

            assertThat(root.getEnabled()).isFalse();
            assertThat(child.getEnabled()).isFalse();
            verify(productCategoryRepository, never()).delete(any(ProductCategory.class));
        }

        @Test
        @DisplayName("refuses to enable a category whose parent is disabled")
        void refusesEnableWithDisabledParent() {
            var disabledRoot = category(UUID.randomUUID(), "Bebidas", null, false, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", disabledRoot, false, 1);
            stubLookup(child);

            assertThatThrownBy(() -> productCategoryService.setCategoryEnabled(companyId, child.getId(), true))
                    .isInstanceOf(DisabledParentCategoryException.class);

            assertThat(child.getEnabled())
                    .as("a refused enable leaves the flag untouched")
                    .isFalse();
            verify(productCategoryRepository, never()).save(any(ProductCategory.class));
        }

        @Test
        @DisplayName("enables a category whose whole ancestor chain is enabled")
        void enablesWhenAncestorsAreEnabled() {
            var root = category(UUID.randomUUID(), "Bebidas", null, true, 1);
            var child = category(UUID.randomUUID(), "Gaseosas", root, false, 1);
            stubLookup(child);
            stubLookup(root);
            stubSaveReturnsArgument();

            var result = productCategoryService.setCategoryEnabled(companyId, child.getId(), true);

            assertThat(result.enabled()).isTrue();
            assertThat(result.depth()).isEqualTo(2);
        }

        @Test
        @DisplayName("reports an unknown category as 404")
        void unknownCategoryIsNotFound() {
            var missingId = UUID.randomUUID();
            when(productCategoryRepository.findByCompanyIdAndId(companyId, missingId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> productCategoryService.setCategoryEnabled(companyId, missingId, false))
                    .isInstanceOf(ProductCategoryNotFoundException.class);
        }
    }
}
