package com.smartroute.algorithms.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The k best items of a stream, using a bounded heap.
 *
 * <p>The heap holds at most k items and its root is the <em>worst</em> of them. Each new item is compared
 * with the root: if it is better, the root is replaced. So the heap always contains the best k seen so far.
 *
 * <ul>
 *   <li>Time O(n log k), space O(k). Sorting everything would be O(n log n) time and O(n) space; for
 *       "top 5 of 10,000 drivers" log k ≈ 2.3 vs log n ≈ 13.3.</li>
 *   <li>Alternative: quickselect, O(n) average but needs all n items in memory and is not stable.</li>
 * </ul>
 *
 * <p>Ties: the comparator must be a total order for a deterministic result (e.g. break ties by id).
 */
public final class TopK {

    private TopK() {
    }

    /**
     * @param better orders items best first (negative when the first argument is better)
     * @return at most k items, best first
     */
    public static <T> List<T> of(Iterable<T> items, int k, Comparator<? super T> better) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0");
        }
        if (k == 0) {
            return List.of();
        }
        // Root = worst kept item, so the heap is ordered by the reverse of "better".
        // Initial capacity is capped: k may be "everything" (Integer.MAX_VALUE), and the heap grows as needed.
        PriorityQueue<T> heap = new PriorityQueue<>(Math.min(k, 64), better.reversed());
        for (T item : items) {
            if (heap.size() < k) {
                heap.add(item);
            } else if (better.compare(item, heap.peek()) < 0) {
                heap.poll();
                heap.add(item);
            }
        }
        List<T> result = new ArrayList<>(heap);
        result.sort(better);
        return result;
    }
}
