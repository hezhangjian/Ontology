package com.hezhangjian.ontology.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsResourceNameConflictsToAnActionableConflict() {
        ResponseEntity<Map<String, Object>> response = handler.connectionProblem(
                new ConnectionProblem("RESOURCE_API_NAME_CONFLICT", "API 名称已被有效资源使用"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("title", "RESOURCE_API_NAME_CONFLICT")
                .containsEntry("detail", "API 名称已被有效资源使用");
        assertThat(response.getBody().get("requestId")).isNotNull();
    }

    @Test
    void mapsDatabaseConstraintsWithoutLeakingAnInternalError() {
        ResponseEntity<Map<String, Object>> response = handler.integrityProblem(
                new DataIntegrityViolationException("duplicate key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("title", "DATA_CONFLICT")
                .doesNotContainEntry("title", "INTERNAL_ERROR");
        assertThat(response.getBody().get("requestId")).isNotNull();
    }

    @Test
    void mapsGeneratedContractValidationFailuresToBadRequests() {
        ResponseEntity<Map<String, Object>> response =
                handler.validationProblem(new ConstraintViolationException(Set.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("title", "CONTRACT_VALIDATION_FAILED")
                .containsEntry("detail", "请求参数不符合 API 合同");
        assertThat(response.getBody().get("requestId")).isNotNull();
    }

    @Test
    void mapsMissingRequiredHeadersToBadRequests() {
        ResponseEntity<Map<String, Object>> response = handler.requestBindingProblem(
                new ServletRequestBindingException("Missing request header 'If-Match'"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("title", "REQUEST_HEADER_INVALID")
                .containsEntry("detail", "请求缺少必填请求头或请求头格式不正确");
        assertThat(response.getBody().get("requestId")).isNotNull();
    }
}
