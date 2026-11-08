package com.verityai.service.knowledge;

import com.verityai.entity.KnowledgeEntity.EntityType;
import com.verityai.entity.KnowledgeRelation.RelationType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic knowledge-graph extraction (Phase 3, Feature 7).
 *
 * <p>No model call is made here. Entities are pattern candidates (dates,
 * metrics, acronyms, proper-noun phrases) merged with the LLM-extracted
 * ENTITIES summary section when one exists. Relations are co-occurrence links
 * inside the same chunk, not semantic assertions. This keeps the graph
 * available offline, bounded, and honest about what it represents.</p>
 */
public final class KnowledgeExtractor {

    /** Hard caps keep the graph (and the API payload) bounded per document. */
    static final int MAX_ENTITIES = 150;
    static final int MAX_RELATIONS = 1500;
    static final int MAX_ENTITIES_PER_CHUNK = 12;
    static final int MAX_NAME_LENGTH = 120;
    static final int EXCERPT_LENGTH = 320;

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "\\b(?:(?:19|20)\\d{2}-\\d{2}-\\d{2}" +
        "|(?:January|February|March|April|May|June|July|August|September|October|November|December)" +
        "\\s+\\d{1,2}(?:,\\s*(?:19|20)\\d{2})?" +
        "|\\d{1,2}\\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)" +
        "\\s+(?:19|20)\\d{2}" +
        "|\\b(?:19|20)\\d{2}\\b)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern METRIC_PATTERN = Pattern.compile(
        "\\b(?:[$€£₹]\\s?\\d[\\d,.]*(?:\\s?(?:million|billion|thousand|bn|m|k))?" +
        "|\\d[\\d,.]*\\s?%" +
        "|\\d[\\d,.]*\\s?(?:million|billion|thousand|bn)\\b)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern ACRONYM_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]{1,7}\\b");

    /**
     * Capitalized phrase with optional lowercase connectors ("Bank of America").
     * Single-token phrases are accepted too and filtered by the stopword list.
     */
    private static final Pattern PROPER_NOUN_PATTERN = Pattern.compile(
        "\\b[A-Z][a-z]+(?:(?:\\s+(?:of|the|and|for|de|van|von|&))?\\s+[A-Z][a-z]+){0,3}\\b");

    /** CamelCase brands such as "VerityAI" or "OpenAI". */
    private static final Pattern MIXED_CASE_PATTERN = Pattern.compile(
        "\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b");

    private static final Set<String> STOPWORDS = Set.copyOf(List.of(
        // articles, pronouns, determiners
        "a", "an", "the", "this", "that", "these", "those", "it", "its", "he", "she",
        "they", "them", "their", "we", "our", "you", "your", "i", "my", "his", "her",
        "who", "whom", "whose", "which", "what", "when", "where", "why", "how",
        // prepositions / conjunctions
        "in", "on", "at", "by", "for", "from", "with", "without", "into", "onto",
        "over", "under", "between", "among", "through", "during", "after", "before",
        "as", "and", "or", "but", "if", "because", "while", "although", "though",
        "than", "then", "so", "yet", "nor", "not", "no", "yes",
        // common sentence starters / discourse markers
        "however", "therefore", "moreover", "furthermore", "additionally", "finally",
        "first", "second", "third", "next", "also", "both", "each", "every", "most",
        "some", "many", "more", "less", "such", "other", "another", "new", "same",
        "there", "here", "now", "today", "yesterday", "tomorrow", "currently",
        "recently", "generally", "typically", "often", "usually", "always", "never",
        "this", "these", "those", "thus", "hence", "instead", "meanwhile", "overall",
        "table", "figure", "section", "chapter", "page", "source", "note", "example",
        // months and weekdays (dates are extracted separately)
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "monday", "tuesday",
        "wednesday", "thursday", "friday", "saturday", "sunday"
    ));

    private static final Set<String> ACRONYM_STOPWORDS = Set.copyOf(List.of(
        "PDF", "API", "APIS", "HTTP", "HTTPS", "URL", "URLS", "UUID", "JSON", "XML",
        "HTML", "CSS", "SQL", "CSV", "AI", "ML", "LLM", "LLMS", "RAG", "OCR", "UI",
        "UX", "ID", "IDS", "OK", "NA", "TBD", "FAQ", "CEO", "CTO", "CFO", "COO",
        "THE", "AND", "FOR", "NOT", "ALL", "ANY", "BUT", "CAN", "YOU", "ARE", "WAS",
        "HAS", "HAD", "ITS", "OUR", "THEIR", "THIS", "THAT", "WITH", "FROM", "INTO",
        "OVER", "ONLY", "MORE", "MOST", "SOME", "SUCH", "THAN", "THEN", "WHEN",
        "WHERE", "WHICH", "WHILE", "WILL", "WOULD", "COULD", "SHOULD", "MAY", "MIGHT",
        "MUST", "SHALL", "BEEN", "BEING", "WERE", "HAVE", "DOES", "DID", "DO", "IS",
        "IT", "AS", "AT", "BY", "IN", "OF", "ON", "OR", "TO", "UP", "WE", "US"
    ));

    private static final Pattern ORG_SUFFIX = Pattern.compile(
        "(?i)\\b(?:inc|corp|corporation|company|co|ltd|limited|llc|plc|gmbh|ag|sa|nv|bv|" +
        "university|institute|department|committee|agency|bank|group|foundation|association|" +
        "organization|organisation|ministry|council|court|school|college|hospital|lab|laboratory|" +
        "center|centre|federal|national|international|bureau|office|authority|commission)\\b");

    private static final Pattern PERSON_TITLE = Pattern.compile(
        "(?i)(?:^|[\\s(])(?:dr|mr|mrs|ms|prof|professor|president|ceo|cto|cfo|director|" +
        "senator|judge|governor|minister|secretary)\\.?\\s+$");

    private KnowledgeExtractor() {
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public record ChunkText(int pageNumber, int chunkIndex, String content) {
    }

    /** A typed name from the LLM ENTITIES summary section. */
    public record TypedName(String name, String type) {
    }

    public record ExtractedEntity(String name, String normalizedName, EntityType type,
                                  int mentionCount, Integer firstPage, String evidenceExcerpt) {
    }

    public record ExtractedRelation(String sourceNormalized, String targetNormalized,
                                    int weight, Integer firstPage, String evidenceExcerpt) {
    }

    public record Extraction(List<ExtractedEntity> entities, List<ExtractedRelation> relations,
                             boolean entitiesTruncated, boolean relationsTruncated) {
    }

    /**
     * Extracts entities and co-occurrence relations from document chunks.
     *
     * @param chunks           document chunks in reading order
     * @param summaryEntities  entities already extracted by the summary model
     *                         (may be empty; never required)
     */
    public static Extraction extract(List<ChunkText> chunks, List<TypedName> summaryEntities) {
        Map<String, MutableEntity> byNormalized = new LinkedHashMap<>();

        for (ChunkText chunk : chunks) {
            if (chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            String text = collapseWhitespace(chunk.content());

            extractMatches(DATE_PATTERN, text, chunk, EntityType.DATE, byNormalized);
            extractMatches(METRIC_PATTERN, text, chunk, EntityType.METRIC, byNormalized);
            extractAcronyms(text, chunk, byNormalized);
            extractMixedCase(text, chunk, byNormalized);
            extractProperNouns(text, chunk, byNormalized);
        }

        // Merge typed entities from the LLM summary. They are richer than the
        // pattern fallback, so a matching CONCEPT/ACRONYM takes the better type.
        if (summaryEntities != null) {
            for (TypedName typed : summaryEntities) {
                if (typed == null || typed.name() == null || typed.name().isBlank()) {
                    continue;
                }
                String name = cleanName(typed.name());
                String normalized = normalize(name);
                if (!isUsable(normalized, name)) {
                    continue;
                }
                EntityType type = parseType(typed.type());
                MutableEntity existing = byNormalized.get(normalized);
                if (existing == null) {
                    MutableEntity created = new MutableEntity(name, normalized, type, 1, null,
                        "Extracted by the document summary model.");
                    byNormalized.put(normalized, created);
                } else if (type != EntityType.OTHER
                        && (existing.type == EntityType.CONCEPT || existing.type == EntityType.ACRONYM)) {
                    existing.type = type;
                    existing.name = name;
                }
            }
        }

        List<MutableEntity> entities = new ArrayList<>(byNormalized.values());
        entities.sort(Comparator
            .comparingInt((MutableEntity e) -> e.mentionCount).reversed()
            .thenComparing(e -> e.normalizedName));

        boolean entitiesTruncated = entities.size() > MAX_ENTITIES;
        if (entitiesTruncated) {
            entities = entities.subList(0, MAX_ENTITIES);
        }

        Map<String, ExtractedEntity> finalEntities = new LinkedHashMap<>();
        for (MutableEntity entity : entities) {
            finalEntities.put(entity.normalizedName, entity.toRecord());
        }

        Map<String, MutableRelation> relations = buildRelations(chunks, finalEntities);
        List<MutableRelation> relationList = new ArrayList<>(relations.values());
        relationList.sort(Comparator
            .comparingInt((MutableRelation r) -> r.weight).reversed()
            .thenComparing(r -> r.sourceNormalized)
            .thenComparing(r -> r.targetNormalized));

        boolean relationsTruncated = relationList.size() > MAX_RELATIONS;
        if (relationsTruncated) {
            relationList = relationList.subList(0, MAX_RELATIONS);
        }

        List<ExtractedEntity> entityRecords = entities.stream().map(MutableEntity::toRecord).toList();
        List<ExtractedRelation> relationRecords = relationList.stream().map(MutableRelation::toRecord).toList();
        return new Extraction(entityRecords, relationRecords, entitiesTruncated, relationsTruncated);
    }

    // ------------------------------------------------------------------
    // Entity extraction
    // ------------------------------------------------------------------

    private static void extractMatches(Pattern pattern, String text, ChunkText chunk,
                                       EntityType type, Map<String, MutableEntity> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String name = cleanName(matcher.group());
            String normalized = normalize(name);
            if (!isUsable(normalized, name)) {
                continue;
            }
            record(out, name, normalized, type, chunk, excerpt(text, matcher.start(), matcher.end()));
        }
    }

    private static void extractAcronyms(String text, ChunkText chunk, Map<String, MutableEntity> out) {
        Matcher matcher = ACRONYM_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            if (ACRONYM_STOPWORDS.contains(name)) {
                continue;
            }
            String normalized = normalize(name);
            if (!isUsable(normalized, name)) {
                continue;
            }
            record(out, name, normalized, EntityType.ACRONYM, chunk,
                excerpt(text, matcher.start(), matcher.end()));
        }
    }

    private static void extractProperNouns(String text, ChunkText chunk, Map<String, MutableEntity> out) {
        Matcher matcher = PROPER_NOUN_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = cleanName(matcher.group());
            String normalized = normalize(name);
            if (!isUsable(normalized, name)) {
                continue;
            }
            if (isAllStopwords(normalized)) {
                continue;
            }
            EntityType type = classifyProperNoun(name, text, matcher.start());
            record(out, name, normalized, type, chunk,
                excerpt(text, matcher.start(), matcher.end()));
        }
    }

    private static void extractMixedCase(String text, ChunkText chunk, Map<String, MutableEntity> out) {
        Matcher matcher = MIXED_CASE_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = cleanName(matcher.group());
            String normalized = normalize(name);
            if (!isUsable(normalized, name)) {
                continue;
            }
            record(out, name, normalized, EntityType.CONCEPT, chunk,
                excerpt(text, matcher.start(), matcher.end()));
        }
    }

    private static boolean isAllStopwords(String normalized) {
        for (String token : normalized.split(" ")) {
            if (!STOPWORDS.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static EntityType classifyProperNoun(String name, String text, int start) {
        if (ORG_SUFFIX.matcher(name).find()) {
            return EntityType.ORGANIZATION;
        }
        String before = text.substring(Math.max(0, start - 40), start);
        if (PERSON_TITLE.matcher(before).find()) {
            return EntityType.PERSON;
        }
        // Two capitalized words without an organization suffix are most often
        // names or places; CONCEPT is the honest generic label for the rest.
        return EntityType.CONCEPT;
    }

    private static void record(Map<String, MutableEntity> out, String name, String normalized,
                               EntityType type, ChunkText chunk, String excerpt) {
        MutableEntity entity = out.get(normalized);
        if (entity == null) {
            out.put(normalized, new MutableEntity(name, normalized, type, 1, chunk.pageNumber(), excerpt));
        } else {
            entity.mentionCount++;
            if (entity.firstPage == null) {
                entity.firstPage = chunk.pageNumber();
            }
            if (entity.evidenceExcerpt == null) {
                entity.evidenceExcerpt = excerpt;
            }
        }
    }

    // ------------------------------------------------------------------
    // Relations
    // ------------------------------------------------------------------

    private static Map<String, MutableRelation> buildRelations(
            List<ChunkText> chunks, Map<String, ExtractedEntity> entities) {
        Map<String, MutableRelation> relations = new LinkedHashMap<>();
        if (entities.size() < 2) {
            return relations;
        }

        for (ChunkText chunk : chunks) {
            if (chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            String normalizedText = " " + normalize(chunk.content()) + " ";

            List<ExtractedEntity> present = new ArrayList<>();
            for (ExtractedEntity entity : entities.values()) {
                if (entity.normalizedName().contains(" ")) {
                    if (normalizedText.contains(" " + entity.normalizedName() + " ")) {
                        present.add(entity);
                    }
                } else if (normalizedText.contains(" " + entity.normalizedName() + " ")) {
                    present.add(entity);
                }
            }
            if (present.size() < 2) {
                continue;
            }
            present.sort(Comparator
                .comparingInt(ExtractedEntity::mentionCount).reversed()
                .thenComparing(ExtractedEntity::normalizedName));
            if (present.size() > MAX_ENTITIES_PER_CHUNK) {
                present = present.subList(0, MAX_ENTITIES_PER_CHUNK);
            }

            String excerpt = excerpt(collapseWhitespace(chunk.content()), 0,
                Math.min(collapseWhitespace(chunk.content()).length(), EXCERPT_LENGTH));
            for (int i = 0; i < present.size(); i++) {
                for (int j = i + 1; j < present.size(); j++) {
                    String a = present.get(i).normalizedName();
                    String b = present.get(j).normalizedName();
                    String source = a.compareTo(b) <= 0 ? a : b;
                    String target = a.compareTo(b) <= 0 ? b : a;
                    String key = source + "\u0000" + target;
                    MutableRelation relation = relations.get(key);
                    if (relation == null) {
                        relations.put(key, new MutableRelation(source, target, 1,
                            chunk.pageNumber(), excerpt));
                    } else {
                        relation.weight++;
                        if (relation.firstPage == null) {
                            relation.firstPage = chunk.pageNumber();
                        }
                    }
                }
            }
        }
        return relations;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isUsable(String normalized, String name) {
        if (normalized.isBlank() || normalized.length() < 2 || normalized.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        // Pure punctuation / symbols are not entities.
        return normalized.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean isSentenceStart(String text, int start) {
        for (int i = start - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '.' || c == '!' || c == '?' || c == ':' || c == '\n' || c == ';';
        }
        return true;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    static String cleanName(String value) {
        if (value == null) {
            return "";
        }
        return collapseWhitespace(value)
            .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
            .trim();
    }

    private static String collapseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String excerpt(String text, int start, int end) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int from = Math.max(0, start - 90);
        int to = Math.min(text.length(), Math.max(end, start) + 180);
        String value = text.substring(from, to).trim();
        return value.length() <= EXCERPT_LENGTH ? value : value.substring(0, EXCERPT_LENGTH) + "…";
    }

    private static EntityType parseType(String value) {
        if (value == null || value.isBlank()) {
            return EntityType.OTHER;
        }
        try {
            return EntityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EntityType.OTHER;
        }
    }

    static RelationType relationType() {
        return RelationType.CO_OCCURS;
    }

    // ------------------------------------------------------------------
    // Mutable work items
    // ------------------------------------------------------------------

    private static final class MutableEntity {
        String name;
        final String normalizedName;
        EntityType type;
        int mentionCount;
        Integer firstPage;
        String evidenceExcerpt;

        MutableEntity(String name, String normalizedName, EntityType type, int mentionCount,
                      Integer firstPage, String evidenceExcerpt) {
            this.name = name;
            this.normalizedName = normalizedName;
            this.type = type;
            this.mentionCount = mentionCount;
            this.firstPage = firstPage;
            this.evidenceExcerpt = evidenceExcerpt;
        }

        ExtractedEntity toRecord() {
            return new ExtractedEntity(name, normalizedName, type, mentionCount, firstPage, evidenceExcerpt);
        }
    }

    private static final class MutableRelation {
        final String sourceNormalized;
        final String targetNormalized;
        int weight;
        Integer firstPage;
        final String evidenceExcerpt;

        MutableRelation(String sourceNormalized, String targetNormalized, int weight,
                        Integer firstPage, String evidenceExcerpt) {
            this.sourceNormalized = sourceNormalized;
            this.targetNormalized = targetNormalized;
            this.weight = weight;
            this.firstPage = firstPage;
            this.evidenceExcerpt = evidenceExcerpt;
        }

        ExtractedRelation toRecord() {
            return new ExtractedRelation(sourceNormalized, targetNormalized, weight, firstPage, evidenceExcerpt);
        }
    }
}
