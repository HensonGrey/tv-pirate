# Migrations — agent notes

Liquibase changelog for the tv-pirate database. Read this before touching any
schema.

## The rules

1. **A DB change MUST be a migration.** Never change the schema by editing an
   entity and expecting the app to fix the DB — `ddl-auto=validate` means
   Hibernate only *checks* the schema against the entities and **crashes on
   mismatch**. Every schema change goes through a changeset in this folder.
2. **Every changeset has an up and a down** (TypeORM style: `up()`/`down()`).
   The SQL body is the up; the `--rollback` line is the down. A changeset
   without a rollback does not ship.
3. **Apply order is explicit.** `master.yaml` includes the files in order. A
   new change = new numbered file + new include line in `master.yaml`.
4. **Applied changesets are immutable.** Never edit a changeset that may have
   already run on a database (checksum mismatch → startup failure). To change
   something, write a *new* changeset.

## How to add a migration

1. Create `NNNN-short-description.sql` here, starting with
   `--liquibase formatted sql`.
2. Changeset id: `NNNN-short-description` (unique forever), author: `tvpirate`.
3. End the changeset with `--rollback <exact inverse of the up>`.
4. Add the file to `master.yaml` (order matters).
5. Restart the backend — Liquibase applies new changesets at startup, before
   Hibernate validation.
6. Always verify up -> down -> up works

## How to roll back

Run from `backend/` via `cmd /c` (PowerShell 5.1 mangles the `-D` args).
Connection config comes from `liquibase.properties` (gitignored — copy
`liquibase.properties.example` first). **Liquibase 5 has ONE `rollback`
goal** — the old `rollbackCount`/`rollback-one-changeset` goals are gone.

```powershell
# preview what the down would do (dry run; SQL goes to target/liquibase/migrate.sql)
.\mvnw.cmd liquibase:rollbackSQL -Dliquibase.propertyFile=liquibase.properties -Dliquibase.rollbackCount=1

# undo the last N changesets for real (runs each changeset's --rollback)
.\mvnw.cmd liquibase:rollback -Dliquibase.propertyFile=liquibase.properties -Dliquibase.rollbackCount=1

# back to a tag instead of a count: first tag the DB, then
# .\mvnw.cmd liquibase:rollback -Dliquibase.propertyFile=liquibase.properties -Dliquibase.rollbackTag=<tag>

# re-apply (the up)
.\mvnw.cmd liquibase:update -Dliquibase.propertyFile=liquibase.properties
```

Then verify up -> down -> up works (rule 6) before committing the migration.

## Baseline note

The `0001-baseline.sql` changesets carry
`--preconditions onFail:MARK_RAN` + `--precondition-sql-check` checks: on
databases that predate Liquibase (the dev DB), the baseline is *marked as
ran* without executing; fresh databases run it for real. Because of that,
**never roll back the baseline changesets on an existing database** — their
rollback would drop tables that were never created by Liquibase.

Formatting gotchas learned the hard way (Liquibase 5 formatted SQL):
- a prose `--` comment line must not START with a directive keyword
  (`changeset`, `rollback`, `comment`, `liquibase`, `preconditions`) — the
  parser treats it as a directive and fails;
- the only precondition supported in formatted SQL is
  `--precondition-sql-check expectedResult:<n> <SQL>` — `tableExists` and
  friends are XML/YAML-changelog vocabulary only, and are silently ignored
  if misspelled;
- the Maven plugin resolves `changeLogFile` against `searchPath` — keep
  `changeLogFile=db/changelog/master.yaml` + `searchPath=src/main/resources`
  in `liquibase.properties`. The paths the CLI parses MUST match the
  `FILENAME` values the app stored (the app runs from classpath:, so it
  stores `db/changelog/...`). On a mismatch, rollback silently reports
  "0 changesets rolled back" — the already-ran filter matches nothing.
