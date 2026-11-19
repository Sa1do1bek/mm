CREATE TABLE resources (
    id uuid PRIMARY KEY,
    tenant_id varchar(100) NOT NULL,
    company_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PUBLISHED', 'ARCHIVED', 'BLOCKED')),
    current_version_id uuid,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resource_versions (
    id uuid PRIMARY KEY,
    resource_id uuid NOT NULL REFERENCES resources(id) ON DELETE RESTRICT,
    tenant_id varchar(100) NOT NULL,
    company_id uuid NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0),
    title varchar(200) NOT NULL,
    original_file_name varchar(255) NOT NULL,
    content_type varchar(150) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 varchar(64) NOT NULL,
    attachment_id uuid NOT NULL UNIQUE REFERENCES attachments(id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL CHECK (status IN ('PUBLISHED', 'HISTORICAL', 'BLOCKED')),
    index_status varchar(20) NOT NULL CHECK (index_status IN ('PENDING', 'RUNNING', 'READY', 'FAILED')),
    index_generation integer NOT NULL DEFAULT 1,
    index_error_code varchar(100),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (resource_id, version_no)
);

ALTER TABLE resources ADD CONSTRAINT fk_resources_current_version
    FOREIGN KEY (current_version_id) REFERENCES resource_versions(id) ON DELETE RESTRICT;

CREATE INDEX idx_resources_company_catalog ON resources(tenant_id, company_id, status, updated_at DESC);
CREATE INDEX idx_resources_company_title ON resources(tenant_id, company_id, lower(title));
CREATE INDEX idx_resource_versions_company ON resource_versions(tenant_id, company_id, resource_id, version_no DESC);

CREATE TABLE resource_knowledge_revisions (
    tenant_id varchar(100) NOT NULL,
    company_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, company_id)
);
