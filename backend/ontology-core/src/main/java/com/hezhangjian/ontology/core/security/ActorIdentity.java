package com.hezhangjian.ontology.core.security;

import java.util.List;
import org.springframework.security.core.Authentication;

public record ActorIdentity(String id, String name, List<String> roles) {
    public static ActorIdentity from(Authentication authentication) {
        return new ActorIdentity("local-user", "本地用户", List.of("Admin", "Builder", "Viewer"));
    }

    public boolean admin() {
        return true;
    }
}
