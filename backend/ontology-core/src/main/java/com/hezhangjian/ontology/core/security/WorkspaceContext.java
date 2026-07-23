package com.hezhangjian.ontology.core.security;

import com.hezhangjian.ontology.core.modeling.OntologyCatalogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class WorkspaceContext extends OncePerRequestFilter {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    public static UUID id() {
        return CURRENT.get() == null ? OntologyCatalogService.DEFAULT_ONTOLOGY_ID : CURRENT.get();
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
        String value = request.getHeader("X-Workspace-Id");
        if (value == null || value.isBlank()) value = request.getHeader("X-Ontology-Id");
        try {
            CURRENT.set(value == null || value.isBlank() ? OntologyCatalogService.DEFAULT_ONTOLOGY_ID : UUID.fromString(value));
            chain.doFilter(request, response);
        } catch (IllegalArgumentException failure) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Workspace id is invalid");
        } finally {
            CURRENT.remove();
        }
    }
}
