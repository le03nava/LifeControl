package com.lifecontrol.api.common.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link WorkerTick}, the mechanical loop of the platform worker (record T44,
 * record T51). Every test drives the tick with a real lambda and real ids — no mock, no Spring
 * context, no clock — because the tick's whole contract is deterministic: which ids reach the action,
 * in what order, how many are counted, and that one bad row cannot abort the batch. That determinism
 * is why W4b was cut at the substrate/execution seam (record T49) and why this half is provable
 * without Keycloak.
 */
@DisplayName("WorkerTick Tests")
class WorkerTickTest {

    private static final Consumer<UUID> NO_OP = id -> {};

    @Nested
    @DisplayName("run — bounding and ordering the batch")
    class BoundingAndOrderingTheBatch {

        @Test
        @DisplayName("should invoke the action only for the first `limit` ids, in the given order")
        void shouldTruncateToTheLimitPreservingOrder() {
            var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            var seen = new ArrayList<UUID>();

            var report = new WorkerTick(2).run(ids, seen::add);

            assertThat(seen)
                    .as("only the first `limit` ids reach the action, FIFO order preserved")
                    .containsExactly(ids.get(0), ids.get(1));
            assertThat(report).isEqualTo(new WorkerTick.TickReport(2, 0));
        }

        @Test
        @DisplayName("should process every id when fewer ids are due than the limit allows")
        void shouldProcessAllWhenFewerThanTheLimit() {
            var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
            var seen = new ArrayList<UUID>();

            var report = new WorkerTick(5).run(ids, seen::add);

            assertThat(seen).containsExactlyElementsOf(ids);
            assertThat(report).isEqualTo(new WorkerTick.TickReport(2, 0));
        }

        @Test
        @DisplayName("should treat an empty batch as a valid tick that invokes nothing")
        void shouldAllowAnEmptyBatch() {
            var calls = new AtomicInteger();

            var report = new WorkerTick(3).run(List.of(), id -> calls.incrementAndGet());

            assertThat(calls).as("an empty batch is a no-op, not an error").hasValue(0);
            assertThat(report).isEqualTo(new WorkerTick.TickReport(0, 0));
        }
    }

    @Nested
    @DisplayName("run — isolating a failing item")
    class IsolatingAFailingItem {

        @Test
        @DisplayName("should continue with the next id when one throws and count exactly one failure")
        void shouldContainOneFailureAndContinue() {
            var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            var failingId = ids.get(1);
            var seen = new ArrayList<UUID>();

            // Nothing is asserted as thrown: a per-item failure must never escape `run`.
            var report = new WorkerTick(5).run(ids, id -> {
                seen.add(id);
                if (id.equals(failingId)) {
                    throw new IllegalStateException("boom");
                }
            });

            assertThat(seen)
                    .as("the id after the failing one is still processed")
                    .containsExactlyElementsOf(ids);
            assertThat(report)
                    .as("attempted counts every id handed to the action, failures included")
                    .isEqualTo(new WorkerTick.TickReport(3, 1));
        }
    }

    @Nested
    @DisplayName("constructor — the batch bound")
    class ConstructorLimitValidation {

        @Test
        @DisplayName("should reject a limit below 1")
        void shouldRejectALimitBelowOne() {
            assertThatThrownBy(() -> new WorkerTick(0))
                    .as("a tick that silently does nothing is a misconfiguration")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WorkerTick(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should accept a limit of exactly 1")
        void shouldAcceptALimitOfOne() {
            assertThat(new WorkerTick(1).run(List.of(UUID.randomUUID()), NO_OP))
                    .isEqualTo(new WorkerTick.TickReport(1, 0));
        }
    }
}
