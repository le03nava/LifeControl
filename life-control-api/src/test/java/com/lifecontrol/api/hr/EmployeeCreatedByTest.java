package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.dto.EmployeeResponse;
import com.lifecontrol.api.hr.model.Employee;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for the write-once contract of {@code employees.created_by}.
 *
 * <p>{@code created_by} answers "who created this employee" and is written exactly once, at creation
 * (spec {@code S2}). The entity therefore exposes no public setter and no builder method for it: the
 * only write path is {@link Employee#markCreatedBy(String)}, which refuses a second write. These
 * tests are plain JUnit — no Spring, no database — because the invariant is in the entity and in the
 * shape of the DTOs, not in the schema.</p>
 */
@DisplayName("Employee created_by — write-once contract")
class EmployeeCreatedByTest {

    @Test
    @DisplayName("markCreatedBy records the actor username on the first write")
    void markCreatedByRecordsTheFirstWrite() {
        var employee = new Employee();

        employee.markCreatedBy("hr-operator");

        assertThat(employee.getCreatedBy()).isEqualTo("hr-operator");
    }

    @Test
    @DisplayName("a second markCreatedBy is refused instead of silently overwriting the creator")
    void markCreatedByRefusesTheSecondWrite() {
        var employee = new Employee();
        employee.markCreatedBy("hr-operator");

        assertThatThrownBy(() -> employee.markCreatedBy("another-operator"))
                .as("created_by is write-once")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("created_by")
                .hasMessageContaining("write-once");

        assertThat(employee.getCreatedBy()).isEqualTo("hr-operator");
    }

    @Test
    @DisplayName("the EmployeeRequest write payload has no createdBy field")
    void employeeRequestCannotTypeCreatedBy() {
        assertThat(Arrays.stream(EmployeeRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList())
                .as("created_by is readable and never typable")
                .doesNotContain("createdBy");
    }

    @Test
    @DisplayName("EmployeeResponse exposes createdBy read-only, immediately before createdAt")
    void employeeResponseExposesCreatedByBeforeCreatedAt() {
        var names = Arrays.stream(EmployeeResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(names).contains("createdBy");
        assertThat(names.indexOf("createdBy"))
                .as("createdBy sits immediately before createdAt")
                .isEqualTo(names.indexOf("createdAt") - 1);
    }
}
