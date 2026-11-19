#!/usr/bin/env python3
"""Apply only V9 while the current backend serves requests; verify before cutover.

Connection files and captured Java output remain private on the deployment host.
The runner uses Flyway from the reviewed immutable application image, not Spring.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import time
import zipfile
import predeploy_backup as backup

BackupError = backup.BackupError
RUNNER = "com/enterprise/mm/warehouse/history/WarehouseProjectionMigrationRunner.class"
STOCK_TABLES = ("stock_balances", "stock_batches", "inventory_cost_layers", "stock_cost_movements",
                "stock_movements", "stock_movement_allocations", "stock_movement_allocation_cost_layers")
COMPANY = "753e618e-8d47-4efd-a41e-df8311b83d2f"


def read_private(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
        raise BackupError("Projection staging requires the private verified deployment backup.")
    return json.loads(path.read_text())


def extract_runner(archive, directory):
    selected, total = [], 0
    for entry in archive.infolist():
        name = entry.filename
        if not (name == "BOOT-INF/classes/" + RUNNER or name.startswith("BOOT-INF/classes/db/migration/")
                or name.startswith("BOOT-INF/lib/")) or entry.is_dir():
            continue
        parts = PurePosixPath(name).parts
        if ".." in parts or name.startswith("/") or "\\" in name or name in selected:
            raise BackupError("Invalid application archive path or duplicate entry.")
        if entry.file_size > 100 * 1024 * 1024:
            raise BackupError("Application archive entry exceeds the staging limit.")
        total += entry.file_size
        if total > 300 * 1024 * 1024:
            raise BackupError("Application archive exceeds the staging limit.")
        selected.append(name)
        relative = name.removeprefix("BOOT-INF/")
        target = directory / relative
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        backup.private_write(target, archive.read(entry))
    if "BOOT-INF/classes/" + RUNNER not in selected:
        raise BackupError("Built image lacks the reviewed standalone projection runner.")


def validate_projection(data, *, check_current=True):
    expected = {"movements": 524583, "source_legs": 661975,
                "semantic_md5": "189a3db4f05fa7a6065b81cbf792c3b8",
                "first_date": "2020-12-31", "last_date": "2026-10-07"}
    if any(data.get(key) != value for key, value in expected.items()):
        raise BackupError("Projected history differs from the independently verified 1C source.")
    before, after, current = (data.get(key) for key in ("before", "after", "current"))
    if (not isinstance(before, dict) or set(before) != set(STOCK_TABLES) or before != after
            or (check_current and before != current)):
        raise BackupError("Current stock fingerprints changed; keep the existing backend running.")
    for fingerprint in before.values():
        if (not isinstance(fingerprint, dict) or not isinstance(fingerprint.get("rows"), int)
                or fingerprint["rows"] < 0 or not re.fullmatch(r"[a-f0-9]{32}", fingerprint.get("md5", ""))):
            raise BackupError("Stock fingerprint proof is incomplete.")


def verify_with_receipt(directory, connection, migrations, proof):
    # The receipt lives beside task backups so future ordinary releases can
    # tolerate legitimate stock transactions after the first verified staging.
    parent = backup.private_directory(directory.parent)
    receipt_path = parent / "warehouse-projection-v9-verified.json"
    identity = hashlib.sha256(json.dumps({key: connection[key] for key in ("host", "port", "database", "username")}, sort_keys=True).encode()).hexdigest()
    expected = {"databaseIdentitySha256": identity, "migration9Checksum": migrations["9"]["checksum"],
                "semanticMd5": "189a3db4f05fa7a6065b81cbf792c3b8", "stockState": proof["before"]}
    if receipt_path.exists():
        if read_private(receipt_path) != expected:
            raise BackupError("Stored staging receipt differs from this database, migration or stock boundary.")
        validate_projection(proof, check_current=False)
    else:
        validate_projection(proof)
        backup.json_write(receipt_path, expected)


def projection_sql():
    current = ",".join("'%s',(select jsonb_build_object('rows',count(*),'md5',md5(coalesce(string_agg(md5(row_to_json(t)::text),'' order by id),''))) from public.%s t where tenant_id='default' and company_id='%s'::uuid)" % (table, table, COMPANY) for table in STOCK_TABLES)
    return """SELECT jsonb_build_object(
      'movements',count(*),'source_legs',sum(source_records),
      'semantic_md5',md5(coalesce(string_agg(md5(concat_ws(':',document_key,movement_date,material_source_id,
          warehouse_source_id,trim_scale(quantity)::text,trim_scale(amount)::text,source_records)),''
          order by document_key collate "C",movement_date,material_source_id collate "C",warehouse_source_id collate "C"),'')),
      'first_date',min(movement_date),'last_date',max(movement_date),
      'before',(select stock_state from public.warehouse_history_projection_state where tenant_id='default' and company_id='%s'::uuid and stage='BEFORE'),
      'after',(select stock_state from public.warehouse_history_projection_state where tenant_id='default' and company_id='%s'::uuid and stage='AFTER'),
      'current',jsonb_build_object(%s))
      FROM public.warehouse_imported_movements WHERE tenant_id='default' and company_id='%s'::uuid""" % (COMPANY, COMPANY, current, COMPANY)


def inspect_current(container, expected):
    state = json.loads(backup.run(["docker", "inspect", container], "Inspect serving backend"))[0]
    if not state.get("State", {}).get("Running") or state.get("Image") != expected:
        raise BackupError("Serving backend changed since backup; stop before migration or cutover.")
    return state


def serving_health():
    opener = backup.request.build_opener(backup.request.ProxyHandler({}), backup.NoRedirect())
    with opener.open("http://127.0.0.1:10320/actuator/health/deployment", timeout=10) as response:
        if response.status != 200 or json.load(response).get("status") != "UP":
            raise BackupError("Current backend is not ready; stop before staged projection.")


def stage(container, directory, configuration, image):
    directory = backup.private_directory(directory)
    if container != "materials-management-backend" or not re.fullmatch(r"[^\s]+:[a-f0-9]{40}", image):
        raise BackupError("Projection staging requires the known container and immutable commit image.")
    verified = read_private(directory / "backup-verified.json")
    saved_preflight = read_private(directory / "database-preflight.json")
    expected_migrations = read_private(directory / "reviewed-migrations.json")
    env_file = directory / "deployment.env"
    backup.validate_environment_snapshot(env_file, directory / "deployment-env-state.txt")
    if backup.checksum(env_file) != verified["deploymentEnvSnapshotSha256"]:
        raise BackupError("Reviewed deployment configuration changed after backup.")
    inspected = inspect_current(container, verified["oldImageId"])
    serving_health()
    new_image = json.loads(backup.run(["docker", "image", "inspect", image], "Inspect built projection image"))[0]
    new_id = new_image.get("Id", "")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", new_id):
        raise BackupError("Invalid built application image identity.")
    with tempfile.TemporaryDirectory(prefix="projection-", dir=directory) as temporary:
        work = Path(temporary)
        current_jar, new_jar = work / "current.jar", work / "new.jar"
        backup.run(["docker", "cp", container + ":/app/app.jar", str(current_jar)], "Read current application")
        os.chmod(current_jar, 0o600)
        if backup.checksum(current_jar) != verified["oldApplicationJarSha256"]:
            raise BackupError("Serving application differs from the preserved backup.")
        shipped = Path(configuration).read_text()
        with zipfile.ZipFile(current_jar) as archive:
            def contents(name):
                path = "BOOT-INF/classes/" + name
                return archive.read(path).decode() if path in archive.namelist() else ""
            connection = backup.runtime_configuration(inspected, shipped, contents("application-prod.yml"),
                contents("application.yml"), [contents("application.properties"), contents("application-prod.properties")])
        temporary_container = backup.run(["docker", "create", "--network", "none", new_id], "Read built application image").strip()
        try:
            backup.run(["docker", "cp", temporary_container + ":/app/app.jar", str(new_jar)], "Read built application")
        finally:
            backup.run(["docker", "rm", temporary_container], "Remove never-started image inspection container")
        os.chmod(new_jar, 0o600)
        with zipfile.ZipFile(new_jar) as archive:
            backup.validate_deployment_configuration(inspected, new_image, env_file, shipped,
                archive.read("BOOT-INF/classes/application.yml").decode(), connection)
            if archive.read("BOOT-INF/classes/application-prod.yml").decode() != shipped:
                raise BackupError("Built application configuration differs from the reviewed configuration.")
            for reviewed in expected_migrations.values():
                entry = "BOOT-INF/classes/db/migration/" + reviewed["script"]
                if backup.flyway_checksum(archive.read(entry)) != reviewed["checksum"]:
                    raise BackupError("Built migration checksums differ from the reviewed sources.")
            extract_runner(archive, work)
        backup.private_write(work / ".pgpass", backup.pgpass(connection))
        pg_image = "postgres:" + str(verified["postgresMajor"]) + "-alpine"
        pg_args = ["-h", connection["host"], "-p", str(connection["port"]), "-U", connection["username"], "-d", connection["database"],
                   "--no-psqlrc", "--no-password", "-At", "-v", "ON_ERROR_STOP=1"]
        def query(sql):
            bounded = "BEGIN READ ONLY; SET LOCAL statement_timeout='60s'; SET LOCAL lock_timeout='5s'; " + sql + "; COMMIT;"
            output = backup.run(backup.client_command(container, work, connection, pg_image, "psql", *pg_args, "-q", "-c", bounded), "Read-only projection verification")
            return json.loads(output)
        columns = backup.precision_columns((Path(configuration).parent / "db/migration/V6__stock_quantity_precision.sql").read_text())
        preflight = query(backup.preflight_sql(columns))
        backup.validate_preflight(preflight, connection, expected_migrations)
        if preflight["history"] != saved_preflight["history"]:
            raise BackupError("Database schema changed after the verified backup.")
        if "9" in preflight["versions"]:
            proof = query(projection_sql())
            verify_with_receipt(directory, connection, expected_migrations, proof)
            serving_health()
            print("Installed projection source and successful staging receipt verified; ordinary deployment may continue.", flush=True)
            return
        if preflight["versions"] != [str(version) for version in range(1, 9)]:
            raise BackupError("Staged projection requires exactly the reviewed schema version 8.")
        backup.json_write(work / "connection.json", connection)
        name = "mm-projection-" + work.name
        command = ["docker", "run", "--rm", "--name", name, "--read-only", "--network", "container:" + container,
                   "--user", str(os.getuid()) + ":" + str(os.getgid()), "--mount", "type=bind,src=" + str(work) + ",dst=/projection,readonly",
                   "--entrypoint", "java", new_id, "-cp", "/projection/classes:/projection/lib/*",
                   RUNNER.removesuffix(".class").replace("/", "."), "/projection/connection.json"]
        print("Applying reviewed V9 projection; the existing MM backend remains online.", flush=True)
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900, check=False)
        except subprocess.TimeoutExpired:
            backup.run(["docker", "stop", "--time", "10", name], "Stop only timed-out projection client")
            raise BackupError("Projection exceeded its bound; verify rollback before retrying.") from None
        backup.private_write(directory / "projection-runner-output.log", result.stdout + result.stderr)
        if result.returncode:
            raise BackupError("Standalone projection failed; keep the existing backend online and inspect private output.")
        after = query(backup.preflight_sql(columns))
        backup.validate_preflight(after, connection, expected_migrations)
        if after["versions"] != [str(version) for version in range(1, 10)]:
            raise BackupError("Projection runner applied an unexpected migration range.")
        proof = query(projection_sql())
        validate_projection(proof)
        inspect_current(container, verified["oldImageId"])
        serving_health()
        verify_with_receipt(directory, connection, expected_migrations, proof)
        backup.json_write(directory / "staged-projection-verified.json", {**proof, "oldImageId": verified["oldImageId"],
            "newImageId": new_id, "newApplicationJarSha256": backup.checksum(new_jar),
            "verifiedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "schemaVersion": 9})
        print(json.dumps({"projectionVerified": True, "movements": proof["movements"], "sourceLegs": proof["source_legs"],
                          "stockFingerprintsUnchanged": True, "existingBackendHealthy": True}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--container", required=True); parser.add_argument("--directory", required=True)
    parser.add_argument("--configuration", required=True); parser.add_argument("--image", required=True)
    args = parser.parse_args()
    try:
        stage(args.container, args.directory, args.configuration, args.image)
    except BackupError as failure:
        print(str(failure), file=sys.stderr)
        print("Projection staging was not verified. The existing backend was not stopped; no connection values were logged.", file=sys.stderr)
        return 1
    except Exception:
        print("Projection staging was not verified. The existing backend was not stopped; no connection values were logged.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__": sys.exit(main())
