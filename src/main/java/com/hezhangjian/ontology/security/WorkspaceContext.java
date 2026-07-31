package com.hezhangjian.ontology.security;

import com.hezhangjian.ontology.service.OntologyLookupService;
import com.hezhangjian.ontology.entity.OntologyEntity;
import com.hezhangjian.ontology.repo.OntologyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class WorkspaceContext extends OncePerRequestFilter {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private static final Pattern ONTOLOGY_PATH = Pattern.compile("^/v1/ontologies/([^/]+)(?:/.*)?$");
    private final ObjectProvider<OntologyRepository> ontologyRepositories;

    public WorkspaceContext(ObjectProvider<OntologyRepository> ontologyRepositories) {
        this.ontologyRepositories = ontologyRepositories;
    }

    public static UUID id() {
        return CURRENT.get() == null ? OntologyLookupService.DEFAULT_ONTOLOGY_ID : CURRENT.get();
    }

    public static <T> T call(UUID workspaceId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        try {
            CURRENT.set(workspaceId);
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void run(UUID workspaceId, Runnable work) {
        call(workspaceId, () -> {
            work.run();
            return null;
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = ontologyIdFromPath(request.getRequestURI());
        try {
            CURRENT.set(resolve(value));
            chain.doFilter(request, response);
        } catch (IllegalArgumentException failure) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Ontology not found");
        } finally {
            CURRENT.remove();
        }
    }

    private UUID resolve(String value) {
        if (value == null || value.isBlank()) return OntologyLookupService.DEFAULT_ONTOLOGY_ID;
        OntologyRepository ontologies = ontologyRepositories.getIfAvailable();
        if (ontologies == null) {
            throw new IllegalArgumentException("Ontology repository is unavailable");
        }
        return ontologies.findByApiName(value)
                .map(OntologyEntity::getInternalId)
                .orElseThrow(IllegalArgumentException::new);
    }

    private String ontologyIdFromPath(String path) {
        Matcher matcher = ONTOLOGY_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
