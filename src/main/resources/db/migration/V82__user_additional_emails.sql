CREATE TABLE user_additional_emails (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email varchar(240) NOT NULL,
    CONSTRAINT pk_user_additional_emails PRIMARY KEY (user_id, email)
);
