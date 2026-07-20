package com.hezhangjian.ontology.core.connections;

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
