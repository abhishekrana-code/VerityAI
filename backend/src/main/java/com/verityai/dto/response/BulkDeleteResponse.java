package com.verityai.dto.response;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BulkDeleteResponse {
    private int deleted;
    /** Ids that were not deleted (already gone or not owned by the caller). */
    private List<UUID> skippedIds;

    public static BulkDeleteResponse of(int deleted, List<UUID> skippedIds) {
        BulkDeleteResponse r = new BulkDeleteResponse();
        r.setDeleted(deleted);
        r.setSkippedIds(skippedIds);
        return r;
    }
}
