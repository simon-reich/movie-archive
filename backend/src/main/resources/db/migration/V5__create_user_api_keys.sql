CREATE TABLE user_api_keys (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    provider      VARCHAR(10)  NOT NULL CHECK (provider IN ('TMDB', 'OMDB')),
    encrypted_key TEXT         NOT NULL,

    CONSTRAINT pk_user_api_keys PRIMARY KEY (id),
    CONSTRAINT fk_uak_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_uak_user_provider UNIQUE (user_id, provider)
);
