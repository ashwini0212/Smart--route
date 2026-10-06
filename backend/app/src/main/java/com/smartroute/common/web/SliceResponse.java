package com.smartroute.common.web;

import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.function.Function;

/**
 * A page of a list that is not counted: the content, where it came from, and whether there is more.
 *
 * <p>{@link PageResponse} carries a total, which costs a {@code count(*)} over everything matching the
 * filter. That is the right trade for the lists a dispatcher reads, which are thousands of rows at most. It
 * is the wrong trade for an append-only log: counting 300k recorded events took 40 ms on every page view,
 * and the answer was already out of date by the time it was rendered, because the relay had published more
 * events in the meantime. Spring Data produces a {@link Slice} by reading one row more than the page size,
 * which is how {@code hasNext} is known without counting anything.
 */
public record SliceResponse<T>(List<T> content, int page, int size, boolean hasNext) {

    public static <E, T> SliceResponse<T> of(Slice<E> slice, Function<E, T> mapper) {
        return new SliceResponse<>(slice.getContent().stream().map(mapper).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}
