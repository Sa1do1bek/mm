#!/usr/bin/env python3
"""Fail-closed MM PostgreSQL backup and new-container health verification.

All connection/configuration values stay in the server's private backup directory.
No application context is started and no database mutations are issued.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
from urllib import parse, request, error
import zipfile
import zlib


class BackupError(Exception):
    pass


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def run(arguments, operation):
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise BackupError(operation + " failed; deployment must stop. Connection values were withheld.")
    return result.stdout.decode("utf-8")


def private_directory(path):
    path = Path(path)
    info = path.lstat()
    if (not path.is_absolute() or not stat.S_ISDIR(info.st_mode) or info.st_mode & 0o077 or path.is_symlink()
        or any(character in str(path) for character in ",\r\n\x00")):
        raise BackupError("Backup directory must be an absolute private directory (0700).")
    return path


def private_write(path, content):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(content.encode("utf-8") if isinstance(content, str) else content)


def json_write(path, data):
    private_write(path, json.dumps(data, indent=2) + "\n")


def environment(config):
    result = {}
    for entry in config.get("Env", []):
        if not isinstance(entry, str) or "=" not in entry:
            raise BackupError("Container environment has an unsupported representation.")
        key, value = entry.split("=", 1)
        if key in result:
            raise BackupError("Container environment has duplicate keys.")
        result[key] = value
    return result


def literal_pool_size(value):
    return re.fullmatch(r"[1-9][0-9]{0,9}", value) is not None and int(value) <= 2147483647


def datasource_defaults(text):
    """Read only the tracked, plain YAML datasource block; reject unsupported YAML."""
    lines = text.splitlines()
    starts = [index for index, line in enumerate(lines) if line == "  datasource:"]
    if len(starts) != 1:
        raise BackupError("Expected one known YAML datasource block.")
    result = {}
    hikari_seen, pool_size_seen, in_hikari = False, False, False
    for line in lines[starts[0] + 1:]:
        if line.strip() and not line.startswith("    "):
            break
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        # Pool capacity does not change the database or credentials being backed up.
        # Accept only this reviewed nested scalar; connection overrides stay rejected.
        if line == "    hikari:" and not hikari_seen:
            hikari_seen = True
            in_hikari = True
            continue
        if line.startswith("      "):
            match = re.fullmatch(r"      maximum-pool-size: ([^\r\n]+)", line)
            if not in_hikari or pool_size_seen or not match or not literal_pool_size(match[1]):
                raise BackupError("Unsupported Hikari YAML configuration; only a literal positive maximum-pool-size is supported.")
            pool_size_seen = True
            continue
        if in_hikari and not pool_size_seen:
            raise BackupError("Hikari YAML configuration must contain a literal maximum-pool-size.")
        in_hikari = False
        match = re.fullmatch(r"    (url|username|password): ([^\r\n]+)", line)
        if not match or match[1] in result or match[2][0] in "\"'&*!{|>":
            raise BackupError("Datasource YAML contains an unsupported override or value syntax.")
        result[match[1]] = match[2].strip()
    if hikari_seen and not pool_size_seen:
        raise BackupError("Hikari YAML configuration must contain a literal maximum-pool-size.")
    if set(result) != {"url", "username", "password"}:
        raise BackupError("Datasource YAML is incomplete.")
    return result


def resolve_value(expression, env):
    match = re.fullmatch(r"\$\{([A-Z][A-Z0-9_]*):([^{}]*)\}", expression)
    if not match:
        raise BackupError("Datasource must use the known environment/default placeholder syntax.")
    return env.get(match[1], match[2])


def validate_yaml_configuration(text, *, prod):
    # This deliberately supports the checked-in plain key/indent syntax only.
    # It is not a general YAML parser: other structures require human review.
    stack, seen = [], set()
    permitted = {"spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "spring.flyway.enabled"} if prod else {"spring.profiles.active"}
    if prod: permitted.add("spring.datasource.hikari.maximum-pool-size")
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"): continue
        if line.lstrip().startswith("- "):
            if any(entry[1] == "spring" for entry in stack):
                raise BackupError("Spring YAML list configuration requires review.")
            continue
        match = re.fullmatch(r"( *)([A-Za-z0-9_.-]+):(?: (.*))?", line)
        if not match:
            raise BackupError("Unsupported running YAML configuration syntax; datasource resolution requires review.")
        indentation, key, value = len(match[1]), match[2], match[3]
        while stack and stack[-1][0] >= indentation: stack.pop()
        path = ".".join([entry[1] for entry in stack] + [key])
        if path in seen: raise BackupError("Duplicate running YAML configuration keys require review.")
        seen.add(path)
        if value is None:
            stack.append((indentation, key))
            continue
        if path == "spring" or (path.startswith("spring.") and value[:1] in "&*!{|>"):
            raise BackupError("Nonliteral Spring YAML mappings require review.")
        if path == "spring.datasource.hikari.maximum-pool-size" and not literal_pool_size(value):
            raise BackupError("Hikari maximum-pool-size must be a literal positive integer.")
        if (path.startswith(("spring.datasource.", "spring.flyway.", "spring.profiles.", "spring.config."))
            and path not in permitted):
            raise BackupError("Unsupported running YAML datasource, Flyway, profile or external-config override.")
        if path in ("spring.datasource", "spring.flyway", "spring.profiles", "spring.config"):
            raise BackupError("Inline datasource, Flyway, profile or external-config mappings require review.")


def runtime_configuration(inspect, shipped_yaml, actual_yaml, base_yaml, property_files):
    config = inspect.get("Config", {})
    env = environment(config)
    if inspect.get("Path") != "java" or inspect.get("Args") != ["-jar", "app.jar"] or config.get("WorkingDir") != "/app":
        raise BackupError("Unsupported runtime command or working directory; resolve datasource manually.")
    forbidden = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "SPRING_APPLICATION_JSON",
                 "SPRING_PROFILES_INCLUDE", "SPRING_FLYWAY_URL", "SPRING_FLYWAY_USER", "SPRING_FLYWAY_PASSWORD",
                 "SPRING_DATASOURCE_JNDI_NAME", "SPRING_DATASOURCE_HIKARI_JDBC_URL",
                 "SPRING_DATASOURCE_HIKARI_USERNAME", "SPRING_DATASOURCE_HIKARI_PASSWORD",
                 "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_CLASS_NAME")
    if any(env.get(key, "").strip() for key in forbidden) or any(value.strip() for key, value in env.items()
        if key.startswith(("SPRING_CONFIG_", "SPRING_PROFILES_GROUP_", "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_"))
        or (key.startswith("SPRING_FLYWAY_") and key != "SPRING_FLYWAY_ENABLED")):
        raise BackupError("Unsupported JVM, datasource, Flyway or external-config overrides; deployment must stop.")
    if env.get("SPRING_PROFILES_ACTIVE", "prod").strip() != "prod" or env.get("SPRING_PROFILES_DEFAULT", "prod").strip() != "prod":
        raise BackupError("Only the known prod profile is supported for automatic backup.")
    if not re.search(r"^    active: \$\{SPRING_PROFILES_ACTIVE:prod\}\s*$", base_yaml, re.M):
        raise BackupError("Running application's default profile differs from the known prod configuration.")
    validate_yaml_configuration(base_yaml, prod=False)
    validate_yaml_configuration(actual_yaml, prod=True)
    if any(re.search(r"^\s*(?:spring\.(?:datasource|flyway|profiles|config)\.|datasource:)", text, re.M)
           for text in property_files):
        raise BackupError("Running application has unsupported property-file datasource/profile overrides.")
    for mount in inspect.get("Mounts", []):
        destination = mount.get("Destination", "").rstrip("/")
        if destination in ("/app", "/app/config", "/config") or destination.startswith("/app/application"):
            raise BackupError("An external configuration mount prevents safe datasource resolution.")
    shipped, actual = datasource_defaults(shipped_yaml), datasource_defaults(actual_yaml)
    if shipped != actual:
        raise BackupError("Running JAR datasource defaults differ from the reviewed configuration.")
    values = {key: env.get("SPRING_DATASOURCE_" + key.upper(), resolve_value(expression, env))
              for key, expression in actual.items()}
    if env.get("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.postgresql.Driver") != "org.postgresql.Driver":
        raise BackupError("Only the reviewed PostgreSQL datasource driver is supported.")
    if any(not value or any(char in value for char in "\r\n\x00") for value in values.values()):
        raise BackupError("Resolved datasource values are empty or contain unsupported control characters.")
    return parse_jdbc(values)


def deployment_environment(path):
    # Docker --env-file does not expand shell syntax. A bare KEY inherits the
    # caller's environment, so that representation is deliberately unsupported.
    result = {}
    path = Path(path)
    if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
        raise BackupError("Deployment env snapshot must be a private readable regular file; an invisible host path is unsupported.")
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.lstrip()
        if not line or line.startswith("#"): continue
        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
        if not match or match[1] in result or "\x00" in match[2]:
            raise BackupError("Deployment env-file syntax requires review; inherited or duplicate values are unsupported.")
        result[match[1]] = match[2]
    return result


def validate_environment_snapshot(path, state_path):
    path, state_path = Path(path), Path(state_path)
    if not state_path.is_file() or state_path.is_symlink() or state_path.stat().st_mode & 0o077:
        raise BackupError("Deployment env snapshot requires a private presence marker.")
    state = state_path.read_text().strip()
    if state not in ("present", "absent") or (state == "absent" and path.stat().st_size != 0):
        raise BackupError("Deployment env snapshot presence marker is invalid.")
    deployment_environment(path)
    return state


def validate_deployment_configuration(inspected, image_info, env_file, shipped_yaml, base_yaml, current):
    env = environment(image_info.get("Config", {}))
    env.update(deployment_environment(env_file))
    env["FLYWAY_ENABLED"] = "true"  # Exact docker run override used by this pipeline.
    if env.get("SPRING_FLYWAY_ENABLED", "true").strip().lower() != "true":
        raise BackupError("Prospective deployment disables Flyway migration; review before stopping the backend.")
    match = re.search(r"^      ddl-auto: \$\{JPA_DDL_AUTO:(none|validate)\}\s*$", shipped_yaml, re.M)
    if not match or env.get("SPRING_JPA_HIBERNATE_DDL_AUTO", env.get("JPA_DDL_AUTO", match[1])).strip().lower() not in ("none", "validate"):
        raise BackupError("Prospective deployment must leave schema changes to reviewed Flyway migrations; Hibernate DDL requires review.")
    if env.get("SPRING_JPA_GENERATE_DDL", "false").strip().lower() != "false":
        raise BackupError("Prospective JPA schema generation is unsupported.")
    for key, value in env.items():
        if not value.strip(): continue
        if (key.startswith("SPRING_JPA_PROPERTIES_") and any(part in key for part in ("HBM2DDL", "SCHEMA_GENERATION"))) or key.startswith("SPRING_SQL_INIT_"):
            if key == "SPRING_SQL_INIT_MODE" and value.strip().lower() == "never": continue
            raise BackupError("Prospective SQL or Hibernate schema initialization override is unsupported.")
        if key in ("SPRING_DATASOURCE_INITIALIZATION_MODE", "SPRING_DATASOURCE_SCHEMA", "SPRING_DATASOURCE_DATA"):
            raise BackupError("Prospective legacy SQL initialization override is unsupported.")
    prospective = {**inspected, "Config": {**inspected["Config"], "Env": [key + "=" + value for key, value in env.items()]}}
    resolved = runtime_configuration(prospective, shipped_yaml, shipped_yaml, base_yaml, ())
    if resolved != current:
        raise BackupError("Prospective deployment datasource differs from the running database being backed up; review the env-file before stopping the backend.")


def parse_jdbc(values):
    url = values["url"]
    if not url.startswith("jdbc:postgresql://") or url.strip() != url:
        raise BackupError("Unsupported JDBC URL; expected a single explicit PostgreSQL host/database.")
    try:
        parsed = parse.urlsplit(url[len("jdbc:"):])
        port = parsed.port or 5432
    except ValueError:
        raise BackupError("Unsupported PostgreSQL address.") from None
    host, database = parsed.hostname, parsed.path.removeprefix("/")
    if (not host or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", host) or host == "0.0.0.0"
        or parsed.username is not None or parsed.password is not None or parsed.fragment
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", database) or not 1 <= port <= 65535):
        raise BackupError("Unsupported PostgreSQL host, database or embedded credentials.")
    try:
        options = parse.parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True) if parsed.query else []
    except ValueError:
        raise BackupError("Malformed JDBC options are not supported.") from None
    if len(dict(options)) != len(options):
        raise BackupError("Duplicate JDBC options are not supported.")
    sslmode = "prefer"
    for key, value in options:
        if key == "sslmode" and value in ("disable", "allow", "prefer", "require"):
            sslmode = value
        elif key == "currentSchema" and value == "public":
            pass
        elif key == "ApplicationName" and value:
            pass
        else:
            raise BackupError("JDBC options require manual review; SSL trust and credentials will not be guessed.")
    return {"host": host, "port": port, "database": database, "username": values["username"],
            "password": values["password"], "sslmode": sslmode}


def pgpass(connection):
    def escape(value):
        return str(value).replace("\\", "\\\\").replace(":", "\\:")
    return ":".join(escape(connection[key]) for key in ("host", "port", "database", "username", "password")) + "\n"


def precision_columns(sql):
    columns = []
    for table, statement in re.findall(r"ALTER TABLE (\w+)\s+([^;]+);", sql):
        columns += [(table, column) for column in re.findall(r"ALTER COLUMN (\w+) TYPE numeric\(22,7\)", statement)]
    if len(columns) != 164 or len(set(columns)) != len(columns):
        raise BackupError("The reviewed V6 quantity migration definition is incomplete or changed.")
    return columns


def flyway_checksum(content):
    # Flyway 10.10 ChecksumCalculator: UTF-8 lines without CR/LF; initial BOM filtered,
    # then java.util.zip.CRC32 returned as signed int. Whitespace/comments are retained.
    text = content.decode("utf-8-sig")
    crc = 0
    for line in re.split(r"\r\n|\r|\n", text):
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc if crc < 2**31 else crc - 2**32


def reviewed_migrations(directory):
    result = {}
    for path in Path(directory).glob("V*__*.sql"):
        match = re.fullmatch(r"V([1-9][0-9]*)__[^/\r\n]+\.sql", path.name)
        if not match: raise BackupError("Unsupported reviewed migration version syntax.")
        if match[1] in result: raise BackupError("Duplicate reviewed migration versions.")
        result[match[1]] = {"script": path.name, "checksum": flyway_checksum(path.read_bytes())}
    if len(result) < 7 or set(result) != {str(version) for version in range(1, len(result) + 1)}:
        raise BackupError("A complete sequential reviewed V1-V7 or later migration source set is required before deployment.")
    return result


def validate_history(records, expected):
    if not isinstance(records, list) or not 5 <= len(records) <= len(expected):
        raise BackupError("Flyway history is outside the reviewed migration prefix.")
    versions = [str(version) for version in range(1, len(records) + 1)]
    for version, row in zip(versions, records):
        if (not isinstance(row, dict) or row.get("version") != version or row.get("type") != "SQL"
            or row.get("script") != expected[version]["script"] or row.get("checksum") != expected[version]["checksum"]):
            raise BackupError("Installed Flyway checksums or migration identities differ from the reviewed sources; resolve before stopping the backend.")
    return versions


def preflight_sql(columns):
    values = ",".join("('%s','%s')" % pair for pair in columns)
    return """WITH declared(table_name,column_name) AS (VALUES %s),
        targets AS (SELECT c.oid,a.attnum,c.relowner,cols.numeric_precision,cols.numeric_scale FROM declared d JOIN pg_namespace n ON n.nspname='public'
            JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=d.table_name
            JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname=d.column_name AND NOT a.attisdropped
            JOIN information_schema.columns cols ON cols.table_schema='public' AND cols.table_name=d.table_name AND cols.column_name=d.column_name),
        saved_view AS (SELECT c.oid,c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relname='warehouse_inventory_reconciliation_preflight' AND c.relkind='v')
        SELECT json_build_object('database',current_database(),'username',current_user,
            'version_num',current_setting('server_version_num')::integer,'size',pg_database_size(current_database()),
            'schema',current_schema(),'columns', (SELECT count(*) FROM targets),
            'owners_ok', (SELECT bool_and(pg_has_role(current_user,relowner,'USAGE')) FROM targets),
            'types_old_ok',(SELECT bool_and(numeric_precision=19 AND numeric_scale=4) FROM targets),
            'types_new_ok',(SELECT bool_and(numeric_precision=22 AND numeric_scale=7) FROM targets),
            'view_present',(SELECT count(*)=1 FROM saved_view),
            'view_owner_ok',(SELECT bool_and(pg_has_role(current_user,relowner,'USAGE')) FROM saved_view),
            'schema_create',has_schema_privilege(current_user,'public','CREATE'),
            'extra_views',(SELECT count(DISTINCT r.ev_class) FROM pg_depend dep JOIN pg_rewrite r ON r.oid=dep.objid
                WHERE ((dep.refobjid,dep.refobjsubid) IN (SELECT oid,attnum FROM targets)
                    OR dep.refobjid IN (SELECT oid FROM saved_view)) AND r.ev_class NOT IN (SELECT oid FROM saved_view)),
            'failed',(SELECT count(*) FROM public.flyway_schema_history WHERE NOT success),
            'versions',(SELECT json_agg(version ORDER BY installed_rank) FROM public.flyway_schema_history WHERE success),
            'history',(SELECT json_agg(json_build_object('version',version,'type',type,'script',script,'checksum',checksum) ORDER BY installed_rank)
                FROM public.flyway_schema_history WHERE success))""" % values


def validate_preflight(data, connection, migrations):
    versions = validate_history(data.get("history"), migrations)
    if data.get("versions") != versions or data.get("failed") != 0:
        raise BackupError("Database Flyway history is not a successful prefix of the reviewed migration sources.")
    types_key = "types_new_ok" if "6" in versions else "types_old_ok"
    if (data.get("database") != connection["database"] or data.get("username") != connection["username"]
        or data.get("schema") != "public" or data.get("columns") != 164
        or any(data.get(key) is not True for key in ("owners_ok", types_key, "view_present", "view_owner_ok", "schema_create"))
        or data.get("extra_views") != 0):
        raise BackupError("Database identity, migration ownership or dependent-view preflight failed.")
    major = int(data.get("version_num", 0)) // 10000
    if not 12 <= major <= 18 or not isinstance(data.get("size"), int) or data["size"] <= 0:
        raise BackupError("Unsupported PostgreSQL major version or database size.")
    return major


def client_command(container, directory, connection, image, program, *arguments, writable=False):
    mount = "type=bind,src=" + str(directory) + ",dst=/backup" + ("" if writable else ",readonly")
    return ["docker", "run", "--rm", "--network", "container:" + container, "--user", str(os.getuid()) + ":" + str(os.getgid()),
            "--mount", mount, "-e", "PGPASSFILE=/backup/.pgpass", "-e", "PGSSLMODE=" + connection["sslmode"],
            "-e", "PGCONNECT_TIMEOUT=10", image, program, *arguments]


def checksum(path):
    hasher = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def backup(container, directory, configuration, migration, deployment_env_file, deployment_env_state_file):
    directory = private_directory(directory)
    if (directory / "backup-verified.json").exists():
        raise BackupError("Use a fresh task directory; an existing backup will not be overwritten.")
    env_state = validate_environment_snapshot(deployment_env_file, deployment_env_state_file)
    inspected = json.loads(run(["docker", "inspect", container], "Inspect current backend"))
    if not isinstance(inspected, list) or len(inspected) != 1 or inspected[0].get("State", {}).get("Running") is not True:
        raise BackupError("The current backend must be running during read-only backup.")
    inspected = inspected[0]
    image_id = inspected.get("Image", "")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image_id):
        raise BackupError("Current backend image identity is invalid.")
    json_write(directory / "container-inspect.json", inspected)
    image_info = json.loads(run(["docker", "image", "inspect", image_id], "Inspect current image"))
    json_write(directory / "image-inspect.json", image_info)
    no_external = "for p in /app/config /config /app/application.properties /app/application.yml /app/application.yaml /app/application-prod.properties /app/application-prod.yml /app/application-prod.yaml; do [ ! -e \"$p\" ] || exit 1; done"
    run(["docker", "exec", container, "/bin/sh", "-ec", no_external], "Check external runtime configuration")
    jar_path = directory / ".runtime-app.jar"
    try:
        run(["docker", "cp", container + ":/app/app.jar", str(jar_path)], "Read running application configuration")
        os.chmod(jar_path, 0o600)
        with zipfile.ZipFile(jar_path) as archive:
            def contents(name):
                path = "BOOT-INF/classes/" + name
                return archive.read(path).decode("utf-8") if path in archive.namelist() else ""
            shipped_yaml, base_yaml = Path(configuration).read_text(), contents("application.yml")
            connection = runtime_configuration(inspected, shipped_yaml, contents("application-prod.yml"),
                base_yaml, [contents("application.properties"), contents("application-prod.properties")])
            validate_deployment_configuration(inspected, image_info[0], deployment_env_file, shipped_yaml, base_yaml, connection)
            migrations = reviewed_migrations(Path(migration).parent)
            runtime_migrations = {}
            for version, reviewed in migrations.items():
                entry = "BOOT-INF/classes/db/migration/" + reviewed["script"]
                if entry in archive.namelist(): runtime_migrations[version] = flyway_checksum(archive.read(entry))
            jar_sha = checksum(jar_path)
        columns = precision_columns(Path(migration).read_text())
        private_write(directory / ".pgpass", pgpass(connection))
        pg_args = ["-h", connection["host"], "-p", str(connection["port"]), "-U", connection["username"], "-d", connection["database"]]
        bootstrap = "postgres:16-alpine"
        run(["docker", "pull", bootstrap], "Prepare read-only PostgreSQL preflight client")
        output = run(client_command(container, directory, connection, bootstrap, "psql", *pg_args,
            "--no-psqlrc", "--no-password", "-At", "-v", "ON_ERROR_STOP=1", "-c", preflight_sql(columns)), "Database migration preflight")
        preflight = json.loads(output)
        major = validate_preflight(preflight, connection, migrations)
        for version in preflight["versions"]:
            if runtime_migrations.get(version) != migrations[version]["checksum"]:
                raise BackupError("Running JAR installed migration sources differ from the reviewed files.")
        json_write(directory / "reviewed-migrations.json", migrations)
        json_write(directory / "database-preflight.json", preflight)
        if shutil.disk_usage(directory).free < preflight["size"] + image_info[0].get("Size", 0) + 256 * 1024 * 1024:
            raise BackupError("Backup filesystem lacks space for the database and rollback image.")
        image = "postgres:" + str(major) + "-alpine"
        run(["docker", "pull", image], "Prepare matching PostgreSQL backup client")
        run(["docker", "image", "save", "--output", str(directory / "backend-image.tar"), image_id], "Preserve current backend image")
        os.chmod(directory / "backend-image.tar", 0o600)
        private_write(directory / "mm-before.dump", b"")
        run(client_command(container, directory, connection, image, "pg_dump", *pg_args,
            "--no-password", "--format=custom", "--file=/backup/mm-before.dump", writable=True), "Complete PostgreSQL backup")
        listing = run(client_command(container, directory, connection, image, "pg_restore", "--list", "/backup/mm-before.dump"), "Verify PostgreSQL archive catalog")
        if any(table not in listing for table in ("TABLE public companies", "TABLE public flyway_schema_history")) or (directory / "mm-before.dump").stat().st_size == 0:
            raise BackupError("PostgreSQL archive verification returned no data.")
        private_write(directory / "archive-list.txt", listing)
        dump_sha = checksum(directory / "mm-before.dump")
        private_write(directory / "mm-before.dump.sha256", dump_sha + "  mm-before.dump\n")
        json_write(directory / "backup-verified.json", {"databaseBackup": "mm-before.dump", "sha256": dump_sha,
            "bytes": (directory / "mm-before.dump").stat().st_size, "postgresMajor": major,
            "oldImageId": image_id, "oldImageSha256": checksum(directory / "backend-image.tar"),
            "oldApplicationJarSha256": jar_sha, "verifiedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "deploymentEnvSourceState": env_state, "deploymentEnvSnapshotSha256": checksum(deployment_env_file),
            "verification": "pg_dump exited successfully; pg_restore --list parsed the complete custom archive. A test restore was not performed.",
            "rollback": "Pause writes and use a coordinated DB restore or forward fix. Do not automatically run old code against exact opening stock."})
        print("MM predeploy backup verified; private database archive and rollback image/configuration preserved.")
    finally:
        for name in (".pgpass", ".runtime-app.jar"):
            (directory / name).unlink(missing_ok=True)


def health(container, expected_image, url, timeout):
    if url != "http://127.0.0.1:10320/actuator/health/deployment" or not re.fullmatch(r"[^\s]+:[a-f0-9]{40}", expected_image):
        raise BackupError("Health verification requires the known local endpoint and immutable commit image tag.")
    deadline = time.monotonic() + timeout
    opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
    while time.monotonic() < deadline:
        try:
            state = json.loads(run(["docker", "inspect", container], "Inspect deployed backend"))[0]
            if state.get("Config", {}).get("Image") != expected_image:
                raise BackupError("Deployed backend image differs from the reviewed commit.")
            if state.get("State", {}).get("Running"):
                with opener.open(url, timeout=5) as response:
                    if response.status == 200 and response.geturl() == url and json.load(response).get("status") == "UP":
                        print("Reviewed MM backend image is healthy after migration."); return
        except (error.URLError, OSError, ValueError, KeyError, IndexError):
            pass
        time.sleep(2)
    raise BackupError("New backend failed health verification; database may already be migrated. No automatic rollback was attempted.")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    before = sub.add_parser("backup")
    before.add_argument("--container", required=True); before.add_argument("--directory", type=Path, required=True)
    before.add_argument("--configuration", type=Path, required=True); before.add_argument("--precision-migration", type=Path, required=True)
    before.add_argument("--deployment-env-file", type=Path, required=True)
    before.add_argument("--deployment-env-state-file", type=Path, required=True)
    after = sub.add_parser("health")
    after.add_argument("--container", required=True); after.add_argument("--image", required=True)
    after.add_argument("--url", default="http://127.0.0.1:10320/actuator/health/deployment"); after.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.container):
        raise BackupError("Unsupported backend container name.")
    if args.command == "backup":
        backup(args.container, args.directory, args.configuration, args.precision_migration, args.deployment_env_file, args.deployment_env_state_file)
    else:
        if not 1 <= args.timeout <= 600: raise BackupError("Health timeout must be between 1 and 600 seconds.")
        health(args.container, args.image, args.url, args.timeout)


if __name__ == "__main__":
    try:
        main()
    except BackupError as exc:
        print("MM deployment check failed: " + str(exc), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, IndexError, zipfile.BadZipFile):
        # Avoid library errors that can include datasource addresses, environment or private file contents.
        print("MM deployment check failed. The running service was not stopped by this helper; no connection values were logged. Resolve the private preflight state before retrying.", file=sys.stderr)
        sys.exit(1)
