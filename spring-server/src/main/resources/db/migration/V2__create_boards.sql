CREATE TABLE board
(
    id         BIGINT  NOT NULL AUTO_INCREMENT,
    title      VARCHAR(255),
    content    LONGTEXT,
    view_count BIGINT  NOT NULL DEFAULT 0,
    version    BIGINT,
    author_id  BIGINT  NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_board_author FOREIGN KEY (author_id) REFERENCES users (id)
) ENGINE = InnoDB;
