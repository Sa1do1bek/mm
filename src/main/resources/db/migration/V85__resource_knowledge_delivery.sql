CREATE TABLE resource_knowledge_events (
    id uuid PRIMARY KEY,
    tenant_id varchar(100) NOT NULL,
    company_id uuid NOT NULL,
    resource_id uuid NOT NULL REFERENCES resources(id),
    version_id uuid NOT NULL REFERENCES resource_versions(id),
    kind varchar(20) NOT NULL CHECK (kind IN ('INDEX', 'VISIBILITY')),
    index_generation integer NOT NULL,
    knowledge_revision bigint NOT NULL,
    visibility_state varchar(20) NOT NULL CHECK (visibility_state IN ('CURRENT', 'HISTORICAL', 'BLOCKED')),
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'WAITING', 'DONE', 'FAILED', 'SUPERSEDED')),
    submitted boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0,
    error_code varchar(100),
    next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, kind, index_generation, knowledge_revision)
);

CREATE INDEX idx_resource_knowledge_due ON resource_knowledge_events(status, next_attempt_at, lease_until);
CREATE INDEX idx_resource_knowledge_scope ON resource_knowledge_events(tenant_id, company_id, kind, status);

-- Existing documents join the same durable pipeline as new uploads.
INSERT INTO resource_knowledge_events(id,tenant_id,company_id,resource_id,version_id,kind,
    index_generation,knowledge_revision,visibility_state)
SELECT gen_random_uuid(),v.tenant_id,v.company_id,r.id,v.id,'VISIBILITY',v.index_generation,
    COALESCE(k.revision,0),
    CASE WHEN r.status <> 'PUBLISHED' OR v.status='BLOCKED' THEN 'BLOCKED'
         WHEN v.id=r.current_version_id THEN 'CURRENT' ELSE 'HISTORICAL' END
FROM resource_versions v JOIN resources r ON r.id=v.resource_id
LEFT JOIN resource_knowledge_revisions k ON k.tenant_id=v.tenant_id AND k.company_id=v.company_id;

INSERT INTO resource_knowledge_events(id,tenant_id,company_id,resource_id,version_id,kind,
    index_generation,knowledge_revision,visibility_state)
SELECT gen_random_uuid(),v.tenant_id,v.company_id,r.id,v.id,'INDEX',v.index_generation,
    COALESCE(k.revision,0),CASE WHEN v.id=r.current_version_id THEN 'CURRENT' ELSE 'HISTORICAL' END
FROM resource_versions v JOIN resources r ON r.id=v.resource_id
LEFT JOIN resource_knowledge_revisions k ON k.tenant_id=v.tenant_id AND k.company_id=v.company_id
WHERE r.status='PUBLISHED' AND v.status IN ('PUBLISHED','HISTORICAL') AND v.index_status <> 'READY';
