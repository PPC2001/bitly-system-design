CREATE TABLE url_mapping (
                             id              BIGINT PRIMARY KEY,
                             short_code      VARCHAR(10) NOT NULL UNIQUE,
                             original_url    TEXT NOT NULL,
                             custom_alias    BOOLEAN DEFAULT FALSE,
                             created_at      TIMESTAMP DEFAULT NOW(),
                             expires_at      TIMESTAMP,
                             created_by      VARCHAR(100),
                             click_count     BIGINT DEFAULT 0,
                             is_active       BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_short_code ON url_mapping(short_code);
CREATE INDEX idx_expires_at ON url_mapping(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE click_analytics (
                                 id              BIGSERIAL PRIMARY KEY,
                                 short_code      VARCHAR(10) NOT NULL,
                                 clicked_at      TIMESTAMP DEFAULT NOW(),
                                 ip_address      VARCHAR(45),
                                 user_agent      TEXT,
                                 referrer        TEXT
);

CREATE INDEX idx_click_short_code ON click_analytics(short_code);