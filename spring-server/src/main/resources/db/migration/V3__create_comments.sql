CREATE TABLE comment
(
    id         BIGINT NOT NULL AUTO_INCREMENT,
    content    LONGTEXT,
    author_id  BIGINT NOT NULL,
    board_id   BIGINT NOT NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_comment_board FOREIGN KEY (board_id) REFERENCES board (id)
) ENGINE = InnoDB;
