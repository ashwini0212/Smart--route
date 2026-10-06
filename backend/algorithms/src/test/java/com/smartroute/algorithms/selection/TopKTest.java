package com.smartroute.algorithms.selection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopKTest {

    private static final Comparator<Integer> HIGHEST_FIRST = Comparator.reverseOrder();

    @Test
    void returnsTheKBestInOrder() {
        assertThat(TopK.of(List.of(5, 1, 9, 3, 7, 9, 2), 3, HIGHEST_FIRST)).containsExactly(9, 9, 7);
    }

    @Test
    void returnsEverythingSortedWhenFewerThanK() {
        assertThat(TopK.of(List.of(2, 8, 4), 10, HIGHEST_FIRST)).containsExactly(8, 4, 2);
    }

    @Test
    void handlesEmptyInputAndKZero() {
        assertThat(TopK.of(List.<Integer>of(), 3, HIGHEST_FIRST)).isEmpty();
        assertThat(TopK.of(List.of(1, 2, 3), 0, HIGHEST_FIRST)).isEmpty();
        assertThatThrownBy(() -> TopK.of(List.of(1), -1, HIGHEST_FIRST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesFullSortOnRandomInput() {
        Random random = new Random(1);
        for (int round = 0; round < 200; round++) {
            List<Integer> values = new ArrayList<>();
            int n = random.nextInt(300);
            for (int i = 0; i < n; i++) {
                values.add(random.nextInt(50)); // many duplicates
            }
            int k = random.nextInt(20);
            List<Integer> expected = values.stream().sorted(HIGHEST_FIRST).limit(k).toList();
            assertThat(TopK.of(values, k, HIGHEST_FIRST)).isEqualTo(expected);
        }
    }

    record Candidate(long id, double score) {
    }

    @Test
    void tieBreakingComparatorGivesADeterministicResult() {
        Comparator<Candidate> better = Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingLong(Candidate::id);
        List<Candidate> candidates = List.of(new Candidate(4, 0.5), new Candidate(2, 0.9), new Candidate(3, 0.5),
                new Candidate(1, 0.5));
        assertThat(TopK.of(candidates, 3, better)).extracting(Candidate::id).containsExactly(2L, 1L, 3L);
    }

    @Test
    void hugeKMeansEverythingWithoutAllocatingK() {
        // Found by the assignment API: k = Integer.MAX_VALUE used to size the heap up front (OutOfMemoryError).
        assertThat(TopK.of(List.of(3, 1, 2), Integer.MAX_VALUE, Comparator.<Integer>naturalOrder()))
                .containsExactly(1, 2, 3);
    }
}
