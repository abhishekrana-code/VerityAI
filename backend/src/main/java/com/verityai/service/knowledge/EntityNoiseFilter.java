package com.verityai.service.knowledge;

import com.verityai.entity.KnowledgeEntity.EntityType;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Drops entities that say nothing about how two documents relate.
 *
 * <p>Deterministic extraction picks up years, two-letter acronyms and common
 * table words ("details", "customer"). A bare year is mentioned in almost every
 * document, so counting it as a shared entity would connect the whole library
 * to itself. The stop list is deliberately small: it only holds words that are
 * structural labels rather than subjects.</p>
 */
public final class EntityNoiseFilter {

    private static final Pattern BARE_YEAR = Pattern.compile("^(?:1[6-9]|20)\\d{2}$");

    private static final Set<String> GENERIC_WORDS = Set.of(
        "details", "code", "customer", "table", "figure", "section", "note",
        "total", "item", "items", "page", "document", "report", "summary",
        "address", "email", "phone", "number", "date", "name", "type"
    );

    private EntityNoiseFilter() {
    }

    /** True when a shared entity must not count towards a document connection. */
    public static boolean isNoise(String name, EntityType type) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (BARE_YEAR.matcher(normalized).matches()) {
            return true;
        }
        if (type == EntityType.ACRONYM && normalized.length() <= 2) {
            return true;
        }
        return GENERIC_WORDS.contains(normalized);
    }
}
