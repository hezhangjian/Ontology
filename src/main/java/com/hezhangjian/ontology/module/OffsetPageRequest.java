package com.hezhangjian.ontology.module;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record OffsetPageRequest(int limit, long offset, Sort sort) implements Pageable {
    public OffsetPageRequest {
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be greater than zero");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative");
        }
        sort = sort == null ? Sort.unsorted() : sort;
    }

    public static OffsetPageRequest of(int limit, long offset, Sort sort) {
        return new OffsetPageRequest(limit, offset, sort);
    }

    @Override
    public int getPageNumber() {
        return Math.toIntExact(offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(limit, offset + limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        if (!hasPrevious()) {
            return first();
        }
        return new OffsetPageRequest(limit, Math.max(offset - limit, 0), sort);
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(limit, 0, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("Page number must not be negative");
        }
        return new OffsetPageRequest(limit, (long) pageNumber * limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
