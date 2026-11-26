create table projects (
    id uuid primary key,
    name varchar(120) not null unique,
    description text not null,
    configuration jsonb not null default '{}',
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null
);
create table resources (
    id uuid primary key,
    project_id uuid not null references projects(id) on delete cascade,
    kind varchar(30) not null check (kind in (
       'connection','sandbox','schema','dataset','query','index-config','index-definition','job','report'
    )),
    name varchar(120) not null,
    data jsonb not null,
    secret text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null,
    unique(project_id, kind, name)
);
create index resources_history on resources(project_id, kind, created_at desc);
create table benchmark_scenarios (
    id uuid primary key,
    job_id uuid not null references resources(id) on delete cascade,
    ordinal integer not null check (ordinal >= 0),
    summary jsonb not null,
    plan jsonb,
    unique(job_id, ordinal)
);
create table benchmark_iterations (
    scenario_id uuid not null references benchmark_scenarios(id) on delete cascade,
    ordinal integer not null check (ordinal >= 0),
    client_ms double precision not null check (client_ms >= 0),
    rows bigint not null check (rows >= 0),
    primary key(scenario_id, ordinal)
);
