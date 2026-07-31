package com.hezhangjian.ontology.exception;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ConnectionProblem.class)
    public ResponseEntity<Map<String, Object>> connectionProblem(
            ConnectionProblem problem) {
        HttpStatus status = switch (problem.code()) {
            case "ETAG_CONFLICT", "ONTOLOGY_ETAG_CONFLICT", "PIPELINE_ETAG_CONFLICT" ->
                    HttpStatus.PRECONDITION_FAILED;
            case "NAME_CONFLICT", "CONNECTION_DISABLED", "CONNECTION_REFERENCED",
                 "DELETE_REQUIRES_DISABLED", "PIPELINE_NAME_CONFLICT", "PIPELINE_RUN_ACTIVE",
                 "PIPELINE_STREAM_ACTIVE",
                 "SAVEPOINT_REQUIRED", "PUBLISHED_API_NAME_IMMUTABLE",
                 "PRIMARY_KEY_IMMUTABLE",
                 "RESOURCE_API_NAME_CONFLICT", "RESOURCE_NOT_DRAFT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, problem.code(), problem.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrityProblem(
            DataIntegrityViolationException problem) {
        UUID requestId = UUID.randomUUID();
        LOG.warn("Ontology request data conflict requestId={} cause={}",
                requestId, problem.getMostSpecificCause().getMessage());
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "当前名称或关联数据已被占用，请刷新页面后重试；已删除的本体资源名称可以直接复用", requestId);
    }

    @ExceptionHandler(ObjectInstanceStoreException.class)
    public ResponseEntity<Map<String, Object>> objectInstanceProblem(
            ObjectInstanceStoreException problem) {
        HttpStatus status = switch (problem.code()) {
            case "OBJECT_INSTANCE_NOT_FOUND", "OBJECT_TYPE_NOT_FOUND",
                 "IMPORT_NOT_FOUND", "RECONCILIATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT", "ETAG_INVALID" -> HttpStatus.PRECONDITION_FAILED;
            case "IDEMPOTENCY_KEY_REUSED", "OBJECT_INSTANCE_EXISTS",
                 "PRIMARY_KEY_IMMUTABLE" -> HttpStatus.CONFLICT;
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

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> resourceNotFound(NoResourceFoundException problem) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "请求的资源不存在");
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Map<String, Object>> multipartProblem(MultipartException problem) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "MULTIPART_LIMIT_EXCEEDED",
                "上传文件数量、分段数量或总大小超过平台限制");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> validationProblem(
            ConstraintViolationException problem) {
        return response(
                HttpStatus.BAD_REQUEST,
                "CONTRACT_VALIDATION_FAILED",
                "请求参数不符合 API 合同");
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    ResponseEntity<Map<String, Object>> requestBindingProblem(
            ServletRequestBindingException problem) {
        return response(
                HttpStatus.BAD_REQUEST,
                "REQUEST_HEADER_INVALID",
                "请求缺少必填请求头或请求头格式不正确");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformedRequest(
            HttpMessageNotReadableException problem) {
        return response(
                HttpStatus.BAD_REQUEST,
                "REQUEST_BODY_INVALID",
                "请求正文不符合 API 合同");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception problem) {
        UUID requestId = UUID.randomUUID();
        LOG.error("Unhandled ontology request failure requestId={}", requestId, problem);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "系统未能完成该操作，请重试；若仍失败，请使用请求编号联系管理员", requestId);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String detail) {
        return response(status, code, detail, UUID.randomUUID());
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String detail,
                                                          UUID requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create("urn:ontology:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        body.put("title", code);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("requestId", requestId.toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
