-- qits-containers, the container registry. V1 is the whole of it: the row that must exist before a
-- container is started, and the volume rows a policy may take with it.
--
-- NO CHECK CONSTRAINTS ON THE ENUM COLUMNS (policy, desired_state, observed_state). The catalogues
-- grow — a lifecycle policy per consumer, an observed state per thing docker can report — and a
-- check constraint turns each addition into a migration that must ship before the code that writes
-- the value. @Enumerated(STRING) on the entity is the invariant, and it is the one that cannot
-- drift from the code. The platform made this decision once already, in qits-platform-deployments'
-- own V1.
--
-- THE SPEC'S ENVIRONMENT IS DELIBERATELY NOT PERSISTED. spec_json holds what a workload IS — image,
-- command, mounts, networks, labels — and never its env, because env carries secrets: a registry
-- token, a database password, a machine token minted for one step. spec_hash is over the same
-- persisted form, so "has this spec changed" is answerable without ever having stored a credential.
-- A container that must be recreated is recreated from the caller's fresh env, not from this table.

create table ct_container (
    -- The row's own identity, and the value the container carries back as a label.
    id uuid primary key,

    -- Insertion order, for listings that must not depend on a random uuid to break a tie.
    seq bigint generated always as identity,

    -- Who asked. The token subject of the calling service (`qits-ci`, `qits-workspaces`), which is
    -- also the scope of every destroy-all call: an owner can only reach its own rows.
    owner varchar(64) not null,

    -- What kind of thing this is to that owner (`step`, `workspace`, `project-agent`). Owner plus
    -- workload plus owner_ref is the PLACE a container occupies.
    workload varchar(64) not null,

    -- The owner's own id for the place: a run id, a workspace id, a project id. Opaque here.
    owner_ref varchar(190) not null,

    -- The docker name. Unique because docker's namespace is, so two rows claiming one name is a
    -- contradiction the database should refuse rather than a race the sweeps discover.
    container_name varchar(190) not null unique,

    -- The image reference as it was run, kept out of spec_json because every listing shows it.
    image text not null,
    spec_json text not null,
    spec_hash varchar(64) not null,

    -- The lifecycle policy this row is swept under (EPHEMERAL, IDLE_STOP, EXPLICIT).
    policy varchar(32) not null,

    -- How long an IDLE_STOP workload may go untouched before it is stopped. Null for the policies
    -- that do not idle out.
    idle_after_s bigint,

    -- The two halves of the restart story: what the caller asked for, and what the last observation
    -- found. A boot sweep reads both and never anything on the host that no row names.
    desired_state varchar(16) not null,
    observed_state varchar(16) not null,

    created_at timestamptz not null,
    updated_at timestamptz not null,

    -- When the observer last got an answer about this container, and when the owner last said it
    -- still wants it. Null until each first happens.
    last_observed_at timestamptz,
    last_touched_at timestamptz,

    -- Append-only human text: why a row failed, what the last docker call said.
    detail text,

    -- The event this row was created by. Nullable, no backfill, and NEVER a foreign key — the event
    -- lives in qits-events' store.
    causation_id uuid
);

-- One live container per place. Partial rather than plain: a deleted row keeps its history with
-- desired_state = 'ABSENT', and the place is free again the moment it is set.
create unique index ct_container_place
    on ct_container (owner, workload, owner_ref)
    where desired_state <> 'ABSENT';

-- Volumes a policy may create and destroy alongside a container. Named by the owner, so the same
-- rule holds: nothing is removed that no row names.
create table ct_volume (
    id uuid primary key,
    owner varchar(64) not null,
    name varchar(190) not null,
    labels_json text,
    desired_state varchar(16) not null,
    created_at timestamptz not null,
    unique (owner, name)
);
