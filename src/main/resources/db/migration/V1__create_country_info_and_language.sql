CREATE TABLE country_info (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    iso_code          VARCHAR(2)   NOT NULL,
    name              VARCHAR(100) NOT NULL,
    capital_city      VARCHAR(100),
    phone_code        VARCHAR(10),
    continent_code    VARCHAR(5),
    currency_iso_code VARCHAR(5),
    country_flag      VARCHAR(255),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_country_info PRIMARY KEY (id),
    CONSTRAINT uk_country_info_iso_code UNIQUE (iso_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_country_info_name ON country_info (name);

CREATE TABLE language (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    country_id BIGINT       NOT NULL,
    iso_code   VARCHAR(10)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    CONSTRAINT pk_language PRIMARY KEY (id),
    CONSTRAINT uk_language_country_iso UNIQUE (country_id, iso_code),
    CONSTRAINT fk_language_country FOREIGN KEY (country_id) REFERENCES country_info (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
