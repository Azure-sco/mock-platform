package com.xuntian.mock.control.release;

public record ReleaseItemRecord(
        long id,
        String releaseId,
        String itemType,
        long objectId,
        long objectVersionId) {
}
