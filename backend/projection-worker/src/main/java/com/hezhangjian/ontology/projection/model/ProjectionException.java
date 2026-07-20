package com.hezhangjian.ontology.projection.model;

public final class ProjectionException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public ProjectionException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public ProjectionException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
