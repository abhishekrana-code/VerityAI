package com.verityai.dto.request;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Explicit graph rebuild request. When {@code documentIds} is empty, all READY
 * documents of the current user are rebuilt (bounded server-side).
 */
@Data
public class RebuildKnowledgeGraphRequest {

    private List<UUID> documentIds;
}
