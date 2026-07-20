package com.hezhangjian.ontology.core.connections;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ConnectionProblemHandler {
    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(AuthorizationDeniedException problem) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前角色无权执行此操作");
    }

    @ExceptionHandler(ConnectionProblem.class)
    ResponseEntity<Map<String, Object>> connectionProblem(ConnectionProblem problem) {
        HttpStatus status = switch (problem.code()) {
            case "NAME_CONFLICT", "VERSION_CONFLICT", "CONNECTION_DISABLED", "CONNECTION_REFERENCED",
                 "DELETE_REQUIRES_DISABLED", "SAVEPOINT_REQUIRED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, problem.code(), problem.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> statusProblem(ResponseStatusException problem) {
        HttpStatus status = HttpStatus.valueOf(problem.getStatusCode().value());
        return response(status, "REQUEST_REJECTED", problem.getReason() == null ? status.getReasonPhrase() : problem.getReason());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception problem) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "请求未能完成，请使用请求编号联系管理员");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String detail) {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create("urn:ontology:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        body.put("title", code);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("requestId", requestId.toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
