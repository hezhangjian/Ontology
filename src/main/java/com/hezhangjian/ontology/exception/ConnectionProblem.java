package com.hezhangjian.ontology.exception;

public final class ConnectionProblem extends RuntimeException {
    private final String code;

    public ConnectionProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
