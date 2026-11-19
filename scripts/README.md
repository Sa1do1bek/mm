# MM deployment backup

The backend deploy job builds and runs the commit SHA image tag. Its `mm-backend-production` resource group serializes backup, replacement and health verification for jobs using this pipeline.

Before replacing the running container, `predeploy_backup.sh backup` creates a new server directory at:

```text
$HOME/.local/share/mm-predeploy/mm-before-<pipeline-id>-<job-id>/
```

The directory is private (0700); stored files are private (0600). Connection values remain on the server and are withheld from helper output. No backup files are uploaded as CI artifacts.

The wrapper snapshots the existing deployment env-file into `deployment.env`, with an explicit `present` or `absent` marker. Genuine absence produces an empty snapshot. Unreadable, unsupported or dangling paths fail before replacement. The snapshot is inside the task directory for both host Python and the official Python-container fallback. The new backend starts using this exact snapshot, so edits to the original env-file cannot change the validated datasource during deployment.

`predeploy_backup.py` resolves the running application's datasource from its actual Docker environment and JAR configuration. It requires the prospective snapshot to resolve to the same database, user, password, profile and JDBC options. Hibernate schema generation must be `none` or `validate`; Flyway must remain enabled. Unsupported profile, JVM, external configuration, datasource and SQL initialization overrides fail closed.

The reviewed nested `spring.datasource.hikari.maximum-pool-size` setting may contain a literal positive integer. Changing pool capacity does not change the database identity checked by the backup. Hikari JDBC URL, credentials, data-source class and data-source properties overrides remain unsupported.

The read-only database preflight requires successful installed Flyway migrations to be a sequential prefix of the shipped reviewed sources, starting with V1 and containing at least V1–V5. Every installed version, SQL script name and signed CRC32 checksum must match both the reviewed source and the current running JAR. V5 upgrades and V6/V7 redeploys are supported. Additional sequential integer migrations can extend the reviewed prefix. Unknown versions, baselines, checksum changes or failed migrations require review before replacement.

The 164 quantity columns must have their reviewed precision: numeric(19,4) before V6 or numeric(22,7) after V6. The preflight also checks migration ownership, the reconciliation view, schema CREATE permission and extra dependent views. It does not measure free space on a separate database server for ALTER TABLE rewrites.

The helper preserves the old image ID and image archive, Docker configuration, reviewed migration evidence, a complete custom PostgreSQL dump, its table-of-contents listing and SHA256. It uses the official PostgreSQL client matching the database major version (12–18). The backend keeps running while `pg_dump` captures a consistent snapshot.

`backup-verified.json` is written only after `pg_dump` succeeds, `pg_restore --list` parses the archive, required MM tables are present and the SHA256 is calculated. **A full test restore is not performed.** Attachment files remain in their existing Docker volume; this helper does not copy that volume.

After replacement, `predeploy_backup.sh health` requires the exact commit image and HTTP 200 with `UP` from `/actuator/health/deployment`. That public GET group exposes status only and includes readiness, database and disk-space checks. Other health component paths remain authenticated; aggregate health indicators remain enabled.

Failures stop the pipeline. The helper never automatically runs the old application against a migrated database. A rollback requires pausing writes and either a coordinated database restore or a forward fix. Writes committed after the dump snapshot are not in that dump. Existing backups are retained and never overwritten automatically.

Standalone verification:

```sh
python3 -m unittest discover -s scripts -p test_predeploy_backup.py
sh -n scripts/predeploy_backup.sh
```

The tests simulate Docker and database responses; they do not access a deployment server. `DeploymentHealthSecurityTest` verifies the actual Spring security rules and production health-group configuration with an isolated test database.

## Read-only production diagnostics

Run a protected `main` pipeline through GitLab **Run pipeline** or its API with
`MM_READ_ONLY_DIAGNOSTICS=1`. This excludes compile, image build/push and deployment;
the only enabled job is `read_only_diagnostics`. Without that flag, the existing
pipeline behavior is unchanged. When adding this diagnostic job during an incident,
use `[skip ci]` in the merge commit message, then explicitly run the flagged pipeline
so the diagnostic change itself does not replace the application.

The job uses existing protected SSH variables and the production resource group.
It reads the fixed backend container's state, immutable image IDs/revisions and
resource use; host disk/load/memory; and exception counts from at most 2,000 log
lines/1 MiB in the last 15 minutes. Only allowlisted exception names and counts are
printed, including Hikari connection-unavailable mentions, fixed connection-failure
phrase counts and the latest numeric total/active/idle/waiting pool state when logged.
These are mentions in a bounded log window, rather than unique failure counts. Raw logs, environments,
credentials, SQL/query text and business response bodies are never printed.

The local deployment health URL and the same public HTTPS health URL are probed
with 3-second connect and 8-second overall curl limits. Reports include HTTP status,
validated health status and connection/TLS/first-byte/total timings. Every SSH probe
has a 15-second limit. Failed probes produce bounded failure labels rather than
invented zero exception counts. No Docker lifecycle operation, configuration edit,
database write, or automatic recovery is performed. The report is diagnostic
evidence, not a claim that deployment health passed.

The database TCP probe privately resolves only JDBC host/port from the running
container's environment, with `SPRING_DATASOURCE_URL` taking precedence over
`DB_URL`. For the reviewed running image `8438cf6d017af84547fa49ef2a5f64e90b732412`,
the shipped production default is used when both are absent. Unknown images,
profile/JVM/external configuration overrides and configuration mounts fail closed.
Password environment fields are filtered out by the server's Docker template and
never reach CI; password defaults are not read. JDBC query options are rejected to
avoid probing a target different from the driver's actual host/port. One TCP socket
from the server host and one from the backend container each have a five-second
limit. Only fixed outcomes and elapsed times are printed; targets stay private.
The probes send no PostgreSQL handshake or credentials and do not establish
PostgreSQL health, query performance or connection-pool availability.

Offline verification:

```bash
python3 -m unittest discover -s scripts -p 'test_*diagnostics.py'
```
