package com.hezhangjian.ontology.core.connections;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ConnectionProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionProblemHandler.class);

    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(AuthorizationDeniedException problem) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前角色无权执行此操作");
    }

    @ExceptionHandler(ConnectionProblem.class)
    ResponseEntity<Map<String, Object>> connectionProblem(ConnectionProblem problem) {
        HttpStatus status = switch (problem.code()) {
            case "NAME_CONFLICT", "VERSION_CONFLICT", "CONNECTION_DISABLED", "CONNECTION_REFERENCED",
                 "DELETE_REQUIRES_DISABLED", "PIPELINE_NAME_CONFLICT", "PIPELINE_RUN_ACTIVE",
                 "PIPELINE_STREAM_ACTIVE", "PIPELINE_VERSION_CONFLICT", "PROPOSAL_CLOSED",
                 "SAVEPOINT_REQUIRED", "ONTOLOGY_VERSION_CONFLICT", "PUBLISHED_API_NAME_IMMUTABLE",
                 "PRIMARY_KEY_IMMUTABLE", "PROPOSAL_BASELINE_CONFLICT", "PROPOSAL_STATE_INVALID",
                 "RESOURCE_NOT_DRAFT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, problem.code(), problem.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> statusProblem(ResponseStatusException problem) {
        HttpStatus status = HttpStatus.valueOf(problem.getStatusCode().value());
        return response(status, "REQUEST_REJECTED", problem.getReason() == null ? status.getReasonPhrase() : problem.getReason());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException problem) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "该资源不支持此操作");
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Map<String, Object>> multipartProblem(MultipartException problem) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "MULTIPART_LIMIT_EXCEEDED",
                "上传文件数量、分段数量或总大小超过平台限制");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception problem) {
        LOG.error("Unhandled ontology-core request failure", problem);
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
