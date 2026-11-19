import copy
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import shutil
import warnings
import zipfile
import predeploy_backup as backup
from test_predeploy_backup import container, preflight, PROD, BASE, ROOT
from stage_warehouse_projection import extract_runner, validate_projection, verify_with_receipt, stage, RUNNER, STOCK_TABLES
BackupError = backup.BackupError


def proof():
    state = {name: {"rows": 1, "md5": "a" * 32} for name in STOCK_TABLES}
    return {"movements": 524583, "source_legs": 661975,
            "semantic_md5": "189a3db4f05fa7a6065b81cbf792c3b8",
            "first_date": "2020-12-31", "last_date": "2026-10-07",
            "before": state, "after": copy.deepcopy(state), "current": copy.deepcopy(state)}


class ProjectionStageTest(unittest.TestCase):
    def test_exact_source_and_all_stock_fingerprints(self):
        validate_projection(proof())

    def test_each_stock_table_must_remain_unchanged(self):
        for table in STOCK_TABLES:
            data = proof(); data["current"][table]["md5"] = "b" * 32
            with self.subTest(table=table), self.assertRaises(BackupError): validate_projection(data)
        data = proof(); del data["before"][STOCK_TABLES[0]]
        with self.assertRaises(BackupError): validate_projection(data)

    def test_rejects_wrong_source_count_digest_or_dates(self):
        for key in ("movements", "source_legs", "semantic_md5", "first_date", "last_date"):
            data = proof(); data[key] = None
            with self.subTest(key=key), self.assertRaises(BackupError): validate_projection(data)

    def test_extracts_only_runner_migrations_and_libraries(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name in ("BOOT-INF/classes/" + RUNNER, "BOOT-INF/classes/db/migration/V9__projection.sql",
                         "BOOT-INF/lib/flyway-core.jar", "BOOT-INF/classes/application-prod.yml"):
                archive.writestr(name, b"fixture")
        with tempfile.TemporaryDirectory() as folder, zipfile.ZipFile(stream) as archive:
            extract_runner(archive, Path(folder))
            self.assertTrue((Path(folder) / "classes" / RUNNER).is_file())
            self.assertTrue((Path(folder) / "lib/flyway-core.jar").is_file())
            self.assertFalse((Path(folder) / "classes/application-prod.yml").exists())
            self.assertEqual((Path(folder) / "classes" / RUNNER).stat().st_mode & 0o777, 0o600)

    def test_rejects_traversal_duplicate_and_missing_runner(self):
        for entries in (["BOOT-INF/lib/../../escape.jar", "BOOT-INF/classes/" + RUNNER],
                        ["BOOT-INF/classes/" + RUNNER] * 2, ["BOOT-INF/lib/flyway.jar"]):
            stream = io.BytesIO()
            with warnings.catch_warnings(), zipfile.ZipFile(stream, "w") as archive:
                warnings.simplefilter("ignore", UserWarning)
                for entry in entries: archive.writestr(entry, b"fixture")
            with tempfile.TemporaryDirectory() as folder, zipfile.ZipFile(stream) as archive:
                with self.subTest(entries=entries), self.assertRaises(BackupError): extract_runner(archive, Path(folder))

    def exercise_stage(self, runner_failure=False, changed_image=False, installed=8):
        import json
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder) / "task"; directory.mkdir(mode=0o700)
            source = directory / "src/main/resources"
            (source / "db/migration").mkdir(parents=True)
            for migration in (ROOT / "src/main/resources/db/migration").glob("*.sql"):
                shutil.copyfile(migration, source / "db/migration" / migration.name)
            configuration = source / "application-prod.yml"; configuration.write_text(PROD)
            current, new = directory / "fixture-current.jar", directory / "fixture-new.jar"
            for path in (current, new):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("BOOT-INF/classes/application.yml", BASE)
                    archive.writestr("BOOT-INF/classes/application-prod.yml", PROD)
                    for migration in (source / "db/migration").glob("*.sql"):
                        archive.writestr("BOOT-INF/classes/db/migration/" + migration.name, migration.read_bytes())
                    archive.writestr("BOOT-INF/classes/" + RUNNER, b"fixture")
            env = directory / "deployment.env"; backup.private_write(env, "")
            backup.private_write(directory / "deployment-env-state.txt", "absent\n")
            backup.json_write(directory / "backup-verified.json", {"oldImageId": container()["Image"],
                "oldApplicationJarSha256": backup.checksum(current), "deploymentEnvSnapshotSha256": backup.checksum(env), "postgresMajor": 17})
            backup.json_write(directory / "reviewed-migrations.json", backup.reviewed_migrations(source / "db/migration"))
            backup.json_write(directory / "database-preflight.json", preflight(installed))
            commands, queries = [], ([preflight(installed), proof()] if installed == 9 else [preflight(installed), preflight(9), proof()])
            def run(args, operation):
                commands.append(args)
                if args[:2] == ["docker", "inspect"]:
                    state = container()
                    if changed_image: state["Image"] = "sha256:" + "c" * 64
                    return json.dumps([state])
                if args[:3] == ["docker", "image", "inspect"]: return json.dumps([{"Id": "sha256:" + "b" * 64}])
                if args[:2] == ["docker", "create"]: return "never-started-fixture"
                if args[:2] == ["docker", "cp"]:
                    shutil.copyfile(current if args[2].startswith("materials-") else new, args[3]); return ""
                if "psql" in args: return json.dumps(queries.pop(0))
                return ""
            def java(args, **kwargs):
                commands.append(args)
                self.assertNotIn("test-only-secret", " ".join(args))
                self.assertIn("--read-only", args)
                self.assertEqual(args[-1], "/projection/connection.json")
                return SimpleNamespace(returncode=1 if runner_failure else 0, stdout=b"captured", stderr=b"")
            with patch("stage_warehouse_projection.backup.run", side_effect=run), patch("stage_warehouse_projection.serving_health"), patch("stage_warehouse_projection.subprocess.run", side_effect=java), patch("sys.stdout", io.StringIO()):
                if runner_failure or changed_image:
                    with self.assertRaises(BackupError): stage("materials-management-backend", directory, configuration, "registry/project:" + "a" * 40)
                    self.assertFalse((directory / "staged-projection-verified.json").exists())
                else:
                    stage("materials-management-backend", directory, configuration, "registry/project:" + "a" * 40)
                    self.assertEqual((directory / "staged-projection-verified.json").exists(), installed == 8)
            self.assertFalse(any(args[:2] in (["docker", "stop"], ["docker", "restart"]) for args in commands))
            if installed == 9 or changed_image: self.assertFalse(any("--entrypoint" in args for args in commands))

    def test_stages_and_verifies_before_any_backend_replacement(self): self.exercise_stage()
    def test_failed_runner_keeps_old_backend_online(self): self.exercise_stage(runner_failure=True)
    def test_changed_serving_image_rejected_before_migration(self): self.exercise_stage(changed_image=True)
    def test_reviewed_installed9_skips_the_one_time_projection(self): self.exercise_stage(installed=9)

    def test_later_stock_changes_require_a_matching_successful_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder) / "task"; directory.mkdir(mode=0o700)
            connection = {"host": "fixture", "port": 5432, "database": "fixture", "username": "fixture"}
            migrations = {"9": {"checksum": 123}}
            changed = proof(); changed["current"][STOCK_TABLES[0]]["rows"] += 1
            with self.assertRaises(BackupError): verify_with_receipt(directory, connection, migrations, changed)
            self.assertFalse((Path(folder) / "warehouse-projection-v9-verified.json").exists())
            verify_with_receipt(directory, connection, migrations, proof())
            verify_with_receipt(directory, connection, migrations, changed)
            changed["semantic_md5"] = "b" * 32
            with self.assertRaises(BackupError): verify_with_receipt(directory, connection, migrations, changed)
            with self.assertRaises(BackupError): verify_with_receipt(directory, connection, {"9": {"checksum": 124}}, proof())


if __name__ == "__main__": unittest.main()
