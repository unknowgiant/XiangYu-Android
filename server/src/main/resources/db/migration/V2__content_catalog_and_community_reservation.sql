CREATE SEQUENCE content_change_seq START WITH 1;

CREATE TABLE content_revisions (
    id bigserial PRIMARY KEY,
    version varchar(40) NOT NULL UNIQUE,
    description varchar(500) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE city_content_entries (
    id uuid PRIMARY KEY,
    city_code varchar(20) NOT NULL,
    category varchar(24) NOT NULL CHECK (category IN (
        'FOOD', 'CULTURE', 'SIGHT', 'HOTEL', 'TRANSPORT', 'TRAVEL_TIP'
    )),
    title varchar(160) NOT NULL,
    summary varchar(2000) NOT NULL,
    source_name varchar(80),
    source_url varchar(2048),
    source_published_at timestamptz,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order smallint NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    revision_id bigint NOT NULL REFERENCES content_revisions(id),
    change_seq bigint NOT NULL DEFAULT nextval('content_change_seq'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    UNIQUE (revision_id, city_code, category, title)
);
CREATE INDEX city_content_lookup_idx
    ON city_content_entries(city_code, category, status, sort_order)
    WHERE deleted_at IS NULL;
CREATE INDEX city_content_changes_idx ON city_content_entries(change_seq);

-- Reserved for a future moderated message board. No write API is enabled in v1.9.6.
CREATE TABLE community_posts (
    id uuid PRIMARY KEY,
    author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id uuid REFERENCES community_posts(id) ON DELETE SET NULL,
    city_code varchar(20),
    subject_id varchar(160),
    content varchar(2000) NOT NULL CHECK (char_length(btrim(content)) BETWEEN 1 AND 2000),
    status varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW', 'VISIBLE', 'HIDDEN', 'REJECTED', 'DELETED')),
    client_request_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    UNIQUE (author_user_id, client_request_id)
);
CREATE INDEX community_posts_feed_idx
    ON community_posts(city_code, created_at DESC)
    WHERE status = 'VISIBLE' AND deleted_at IS NULL;
CREATE INDEX community_posts_author_idx ON community_posts(author_user_id, created_at DESC);

CREATE TABLE community_reports (
    id uuid PRIMARY KEY,
    post_id uuid NOT NULL REFERENCES community_posts(id) ON DELETE CASCADE,
    reporter_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason varchar(32) NOT NULL CHECK (reason IN (
        'SPAM', 'ABUSE', 'PERSONAL_INFORMATION', 'ILLEGAL_CONTENT', 'MISINFORMATION', 'OTHER'
    )),
    detail varchar(500),
    status varchar(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);
CREATE UNIQUE INDEX community_reports_open_unique_idx
    ON community_reports(post_id, reporter_user_id)
    WHERE status = 'OPEN';

CREATE TABLE community_moderation_events (
    id bigserial PRIMARY KEY,
    post_id uuid NOT NULL REFERENCES community_posts(id) ON DELETE CASCADE,
    action varchar(24) NOT NULL CHECK (action IN (
        'APPROVE', 'HIDE', 'REJECT', 'RESTORE', 'DELETE'
    )),
    operator_ref varchar(120) NOT NULL,
    reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_moderation_post_idx
    ON community_moderation_events(post_id, created_at DESC);
