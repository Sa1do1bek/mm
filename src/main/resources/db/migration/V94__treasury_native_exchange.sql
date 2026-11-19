-- Treasury is an independent, native-initiated transport. No warehouse/IFRS tables are changed.
create table treasury_native_mapping (
 tenant_id varchar(120) not null, company_id uuid not null, mm_payment_id uuid not null,
 source_base_id varchar(128) not null, payload text not null, updated_at timestamptz not null default current_timestamp,
 primary key(tenant_id,company_id,mm_payment_id)
);
create table treasury_native_request (
 id uuid primary key,tenant_id varchar(120) not null,company_id uuid not null,source_base_id varchar(128) not null,
 native_request_id uuid, native_number varchar(240),mm_payment_id uuid,origin varchar(16) not null,
 native_organization_id uuid not null,operation_type varchar(128) not null,
 amount numeric(19,4) not null check(amount>0),currency varchar(3) not null,purpose text,
 approval_status varchar(160),bank_status varchar(160),revision bigint not null default 0,snapshot text not null default '{}',
 last_error text,quarantined boolean not null default false,created_at timestamptz not null default current_timestamp,updated_at timestamptz not null default current_timestamp,
 unique(tenant_id,company_id,source_base_id,native_request_id),unique(tenant_id,company_id,mm_payment_id)
);
create table treasury_native_task (
 id uuid primary key,tenant_id varchar(120) not null,company_id uuid not null,source_base_id varchar(128) not null,
 request_id uuid not null references treasury_native_request(id),event_id uuid not null unique,
 payload text not null,payload_hash varchar(64) not null,state varchar(24) not null default 'QUEUED',
 worker_id varchar(128),lease_token uuid,lease_expires_at timestamptz,attempt_count integer not null default 0,
 result text,result_hash varchar(64),last_error text,created_at timestamptz not null default current_timestamp,
 updated_at timestamptz not null default current_timestamp, unique(request_id)
);
create index treasury_native_task_due on treasury_native_task(tenant_id,company_id,source_base_id,state,created_at);
create table treasury_native_event (
 id uuid primary key,tenant_id varchar(120) not null,company_id uuid not null,source_base_id varchar(128) not null,
 event_id varchar(160) not null,event_type varchar(40) not null,native_request_id uuid,revision bigint not null,
 payload text not null,payload_hash varchar(64) not null,created_at timestamptz not null default current_timestamp,
 unique(tenant_id,company_id,source_base_id,event_id)
);
create table treasury_native_settlement (
 id uuid primary key,request_id uuid not null references treasury_native_request(id),tenant_id varchar(120) not null,
 company_id uuid not null,source_base_id varchar(128) not null,native_payment_id varchar(240) not null,
 kind varchar(16) not null check(kind in ('SETTLED','REVERSED')),original_payment_id varchar(240),
 amount numeric(19,4) not null check(amount>0),currency varchar(3) not null,payment_date date not null,
 source_kind varchar(40) not null,bank_reference varchar(240),fact_hash varchar(64) not null,
 created_at timestamptz not null default current_timestamp,unique(tenant_id,company_id,source_base_id,native_payment_id)
);
