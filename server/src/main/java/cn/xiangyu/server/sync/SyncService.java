package cn.xiangyu.server.sync;

import cn.xiangyu.server.api.ApiException;
import cn.xiangyu.server.security.UserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static cn.xiangyu.server.sync.SyncDtos.*;

@Service
public class SyncService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CursorCodec cursors;
    private final SyncPayloadValidator payloadValidator;

    public SyncService(JdbcTemplate jdbc, ObjectMapper objectMapper, CursorCodec cursors,
                       SyncPayloadValidator payloadValidator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
        this.payloadValidator = payloadValidator;
    }

    @Transactional
    public SyncResponse synchronize(UserPrincipal principal, SyncRequest request) {
        requireActive(principal);
        if (!principal.deviceId().equals(request.deviceId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_MISMATCH", "登录设备与同步设备不一致");
        }
        long cursor = cursors.decode(request.cursor());
        List<SyncAck> accepted = new ArrayList<>();
        List<SyncConflict> conflicts = new ArrayList<>();
        for (SyncMutation mutation : request.changes()) {
            applyMutation(principal.userId(), mutation, accepted, conflicts);
        }
        PullResult pull = pull(principal.userId(), cursor, request.effectiveLimit());
        return new SyncResponse(Instant.now(), cursors.encode(pull.nextSequence()), pull.hasMore(),
                accepted, conflicts, pull.entities());
    }

    public ChangePage bootstrap(UserPrincipal principal, String cursor, int limit) {
        requireActive(principal);
        long sequence = cursors.decode(cursor);
        PullResult pull = pull(principal.userId(), sequence, Math.max(1, Math.min(200, limit)));
        return new ChangePage(cursors.encode(pull.nextSequence()), pull.hasMore(), pull.entities());
    }

    private void applyMutation(UUID userId, SyncMutation mutation, List<SyncAck> accepted,
                               List<SyncConflict> conflicts) {
        List<SyncAck> repeated = jdbc.query("""
                SELECT mutation_id,entity_id,version FROM sync_mutations
                WHERE user_id=? AND mutation_id=?
                """, (rs, rowNum) -> new SyncAck(rs.getObject("mutation_id", UUID.class),
                rs.getObject("entity_id", UUID.class), rs.getLong("version")),
                userId, mutation.mutationId());
        if (!repeated.isEmpty()) {
            accepted.add(repeated.get(0));
            return;
        }

        List<EntityRow> rows = jdbc.query("""
                SELECT * FROM sync_entities WHERE user_id=? AND id=? FOR UPDATE
                """, (rs, rowNum) -> row(rs), userId, mutation.entityId());
        EntityRow current = rows.isEmpty() ? null : rows.get(0);
        if (current != null && current.entityType() != mutation.entityType()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ENTITY_TYPE_MISMATCH", "同步条目类型不一致");
        }
        if (current == null && mutation.baseVersion() != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BASE_VERSION", "同步条目的基础版本不存在");
        }
        if (current != null && current.version() != mutation.baseVersion()) {
            conflicts.add(new SyncConflict(mutation.mutationId(), mutation.entityId(),
                    "SYNC_CONFLICT", current.view()));
            return;
        }

        long version = current == null ? 1 : current.version() + 1;
        Long sequence = jdbc.queryForObject("SELECT nextval('sync_change_seq')", Long.class);
        if (sequence == null) throw new IllegalStateException("Unable to allocate sync sequence");
        Instant now = Instant.now();
        if (mutation.operation() == Operation.DELETE) {
            if (mutation.payload() != null && !mutation.payload().isNull()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SYNC_PAYLOAD",
                        "删除操作不能包含内容");
            }
            saveDelete(userId, mutation, current, version, sequence, now);
        } else {
            SyncPayloadValidator.Metadata metadata = payloadValidator.validate(
                    mutation.entityType(), mutation.payload());
            saveUpsert(userId, mutation, metadata, version, sequence, now);
        }
        jdbc.update("""
                INSERT INTO sync_mutations(user_id,mutation_id,entity_id,version,created_at)
                VALUES (?,?,?,?,?)
                """, userId, mutation.mutationId(), mutation.entityId(), version, Timestamp.from(now));
        accepted.add(new SyncAck(mutation.mutationId(), mutation.entityId(), version));
    }

    private void saveUpsert(UUID userId, SyncMutation mutation, SyncPayloadValidator.Metadata metadata,
                            long version, long sequence, Instant now) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(mutation.payload());
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SYNC_PAYLOAD", "同步内容无法读取");
        }
        jdbc.update("""
                INSERT INTO sync_entities(user_id,id,entity_type,subject_id,city_code,category,payload,
                  version,change_seq,client_updated_at,server_updated_at,deleted_at)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,NULL)
                ON CONFLICT(user_id,id) DO UPDATE SET entity_type=excluded.entity_type,
                  subject_id=excluded.subject_id,city_code=excluded.city_code,category=excluded.category,
                  payload=excluded.payload,version=excluded.version,change_seq=excluded.change_seq,
                  client_updated_at=excluded.client_updated_at,server_updated_at=excluded.server_updated_at,
                  deleted_at=NULL
                """, userId, mutation.entityId(), mutation.entityType().name(), metadata.subjectId(),
                metadata.cityCode(), metadata.category(), payload, version, sequence,
                Timestamp.from(mutation.clientUpdatedAt()), Timestamp.from(now));
    }

    private void saveDelete(UUID userId, SyncMutation mutation, EntityRow current,
                            long version, long sequence, Instant now) {
        jdbc.update("""
                INSERT INTO sync_entities(user_id,id,entity_type,subject_id,city_code,category,payload,
                  version,change_seq,client_updated_at,server_updated_at,deleted_at)
                VALUES (?,?,?,?,?,?,NULL,?,?,?,?,?)
                ON CONFLICT(user_id,id) DO UPDATE SET payload=NULL,version=excluded.version,
                  change_seq=excluded.change_seq,client_updated_at=excluded.client_updated_at,
                  server_updated_at=excluded.server_updated_at,deleted_at=excluded.deleted_at
                """, userId, mutation.entityId(), mutation.entityType().name(),
                current == null ? null : current.subjectId(), current == null ? null : current.cityCode(),
                current == null ? null : current.category(), version, sequence,
                Timestamp.from(mutation.clientUpdatedAt()), Timestamp.from(now), Timestamp.from(now));
    }

    private PullResult pull(UUID userId, long cursor, int limit) {
        List<EntityRow> rows = jdbc.query("""
                SELECT * FROM sync_entities WHERE user_id=? AND change_seq>?
                ORDER BY change_seq ASC LIMIT ?
                """, (rs, rowNum) -> row(rs), userId, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        long next = rows.isEmpty() ? cursor : rows.get(rows.size() - 1).changeSequence();
        return new PullResult(next, hasMore, rows.stream().map(EntityRow::view).toList());
    }

    private EntityRow row(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        JsonNode payload = null;
        String raw = rs.getString("payload");
        if (raw != null) {
            try {
                payload = objectMapper.readTree(raw);
            } catch (JsonProcessingException exception) {
                throw new SQLException("Stored sync payload is invalid", exception);
            }
        }
        Number categoryValue = (Number) rs.getObject("category");
        return new EntityRow(rs.getObject("id", UUID.class),
                EntityType.valueOf(rs.getString("entity_type")), rs.getString("subject_id"),
                rs.getString("city_code"), categoryValue == null ? null : categoryValue.intValue(), payload,
                rs.getLong("version"), rs.getLong("change_seq"),
                rs.getTimestamp("server_updated_at").toInstant(), deletedAt != null);
    }

    private static void requireActive(UserPrincipal principal) {
        if (!"ACTIVE".equals(principal.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "账号当前不能同步数据");
        }
    }

    private record PullResult(long nextSequence, boolean hasMore, List<SyncEntity> entities) { }

    private record EntityRow(UUID id, EntityType entityType, String subjectId, String cityCode,
                             Integer category, JsonNode payload, long version, long changeSequence,
                             Instant serverUpdatedAt, boolean deleted) {
        SyncEntity view() {
            return new SyncEntity(id, entityType, version, serverUpdatedAt, deleted, payload);
        }
    }
}
