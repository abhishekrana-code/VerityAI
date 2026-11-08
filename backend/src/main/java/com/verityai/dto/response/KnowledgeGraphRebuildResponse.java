package com.verityai.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class KnowledgeGraphRebuildResponse {

    private int requested;

    private int rebuilt;

    private int skipped;

    private List<UUID> rebuiltDocumentIds = new ArrayList<>();

    private List<String> errors = new ArrayList<>();
}
