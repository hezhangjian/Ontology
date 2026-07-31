package com.hezhangjian.ontology.instance;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class SqlIdentifierResolver {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    public String tableName(String physicalKey) {
        String suffix = requireSafe(physicalKey).toLowerCase(Locale.ROOT);
        String value = "object_type_" + suffix;
        return value.length() <= 63 ? value : value.substring(0, 63);
    }

    public String quote(String identifier) {
        return '"' + requireSafe(identifier) + '"';
    }

    public String qualified(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    public String requireSafe(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier");
        }
        return identifier;
    }
}
