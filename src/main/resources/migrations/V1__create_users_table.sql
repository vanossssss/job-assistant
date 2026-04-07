CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255),
    avatar_url VARCHAR(500),
    google_id VARCHAR(255) UNIQUE,
    created_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP
);