package cn.xiangyu.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SyncDtos {
    private SyncDtos() { }

    public enum EntityType { FAVORITE, NOTE, CUSTOM_ITEM, JOURNAL }
    public enum Operation { UPSERT, DELETE }

    public record SyncMutation(
            @NotNull UUID mutationId,
            @NotNull UUID entityId,
            @NotNull EntityType entityType,
            @NotNull Operation operation,
            @PositiveOrZero long baseVersion,
            @NotNull Instant clientUpdatedAt,
            JsonNode payload) { }

    public record SyncRequest(
            @NotNull UUID deviceId,
            @Size(max = 2048) String cursor,
            @NotNull @Size(max = 200) List<@Valid SyncMutation> changes,
            @Min(1) @Max(200) Integer limit) {
        public int effectiveLimit() { return limit == null ? 200 : limit; }
    }

    public record SyncAck(UUID mutationId, UUID entityId, long version) { }

    public record SyncConflict(UUID mutationId, UUID entityId, String code, SyncEntity remote) { }

    public record SyncEntity(UUID entityId, EntityType entityType, long version,
                             Instant serverUpdatedAt, boolean deleted, JsonNode payload) { }

    public record SyncResponse(Instant serverTime, String nextCursor, boolean hasMore,
                               List<SyncAck> accepted, List<SyncConflict> conflicts,
                               List<SyncEntity> changes) { }

    public record ChangePage(String nextCursor, boolean hasMore, List<SyncEntity> changes) { }
}
