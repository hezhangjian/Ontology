package com.hezhangjian.ontology.instance;

public final class ObjectInstanceStoreException extends RuntimeException {
    private final String code;

    public ObjectInstanceStoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ObjectInstanceStoreException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
