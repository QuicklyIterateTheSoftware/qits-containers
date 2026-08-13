-- The container name is unique among LIVE rows, exactly as the place already is.
--
-- V1 declared `container_name varchar(190) not null unique` — table-wide — one line above a place
-- index that is deliberately partial. The two disagreed, and the name is the half that was wrong.
--
-- A delete here is soft: the row stays with desired_state = 'ABSENT', keeps the name it was started
-- under, and RowPrune releases it only a horizon (P7D) after the GONE settle. ContainerNames.of() is
-- deterministic per place and consumers pass deterministic explicit names too, so EVERY
-- delete-then-ensure of one place asked for the name a settled row was still holding. What came back
-- was a raw 23505 that DbRetry rethrows: an unmapped 500 with no code on it, for seven days per
-- place. It stayed invisible because EPHEMERAL ci steps key their places by run id and never
-- re-ensure a deleted one — the flows it really broke are a consumer's streamed recreate, its
-- delete-then-start, and its retry after a failed provision.
--
-- Partial is the same treatment the place index has and the same statement: a settled row keeps its
-- recorded name for history, and the NAME is free the moment the PLACE is. The collision that is
-- honest — an ensure whose name a LIVE row of a DIFFERENT place holds — is still refused, by this
-- index and, before it, by a check in the registry that answers NAME_TAKEN rather than leaving the
-- database to say it as a 500.
--
-- Dropped by the name postgres gave it (`<table>_<column>_key`, chosen when V1 declared the column
-- unique) and NOT `if exists`: a table whose constraint is spelled differently is one this migration
-- must fail on rather than leave the old constraint standing under a new index that hides it.
--
-- No backfill. This only widens what the table accepts, so every row already in it still passes.

alter table ct_container drop constraint ct_container_container_name_key;

create unique index ct_container_name_live
    on ct_container (container_name)
    where desired_state <> 'ABSENT';
