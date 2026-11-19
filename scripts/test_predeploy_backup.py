import contextlib
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from predeploy_backup import (BackupError, NoRedirect, backup, checksum, client_command, datasource_defaults,
                              deployment_environment, flyway_checksum, health, parse_jdbc, pgpass, precision_columns,
                              private_directory, reviewed_migrations, runtime_configuration,
                              validate_deployment_configuration, validate_environment_snapshot, validate_history, validate_preflight,
                              validate_yaml_configuration)

ROOT = Path(__file__).resolve().parent.parent
BASE = "spring:\n  profiles:\n    active: ${SPRING_PROFILES_ACTIVE:prod}\n"
PROD = "spring:\n  datasource:\n    url: ${DB_URL:jdbc:postgresql://db.internal:5432/mmdb}\n    username: ${DB_USERNAME:mmuser}\n    password: ${DB_PASSWORD:test-only-secret}\n  flyway:\n    enabled: ${FLYWAY_ENABLED:true}\n  jpa:\n    hibernate:\n      ddl-auto: ${JPA_DDL_AUTO:none}\n"


def container():
    return {"Path": "java", "Args": ["-jar", "app.jar"], "Image": "sha256:" + "a" * 64,
            "State": {"Running": True}, "Config": {"Env": [], "WorkingDir": "/app"}, "Mounts": []}


def preflight(installed=5):
    return {"database": "mmdb", "username": "mmuser", "version_num": 160004, "size": 1024,
            "schema": "public", "columns": 164, "owners_ok": True, "types_old_ok": installed < 6,
            "types_new_ok": installed >= 6, "view_present": True,
            "view_owner_ok": True, "schema_create": True, "extra_views": 0,
            "failed": 0, "versions": [str(version) for version in range(1, installed + 1)],
            "history": [{"version": version, "type": "SQL", **row}
                        for version, row in sorted(reviewed_migrations(ROOT / "src/main/resources/db/migration").items(), key=lambda item:int(item[0]))
                        if int(version) <= installed]}


class PredeployBackupTest(unittest.TestCase):
    def resolve(self, state=None, shipped=PROD, actual=PROD, base=BASE, properties=()):
        return runtime_configuration(state or container(), shipped, actual, base, properties)

    def test_running_jar_defaults_and_explicit_spring_environment_precedence(self):
        result = self.resolve()
        self.assertEqual((result["host"], result["database"], result["username"]), ("db.internal", "mmdb", "mmuser"))
        state = container(); state["Config"]["Env"] = ["DB_PASSWORD=fallback", "SPRING_DATASOURCE_PASSWORD=explicit"]
        self.assertEqual(self.resolve(state)["password"], "explicit")
        state["Config"]["Env"].append("DB_URL=jdbc:postgresql://other.internal:6432/mmdb?sslmode=require&currentSchema=public")
        self.assertEqual((self.resolve(state)["port"], self.resolve(state)["sslmode"]), (6432, "require"))

    def test_changed_running_defaults_cannot_choose_a_different_backup_database(self):
        with self.assertRaises(BackupError): self.resolve(actual=PROD.replace("mmdb", "otherdb"))
        with self.assertRaises(BackupError): datasource_defaults(PROD.replace("${DB_PASSWORD:test-only-secret}", "'quoted'"))

    def test_literal_hikari_pool_size_preserves_old_and_new_database_identity(self):
        prospective = PROD.replace("  flyway:\n", "    hikari:\n      maximum-pool-size: 30\n  flyway:\n")
        current = self.resolve()
        self.assertEqual(datasource_defaults(prospective), datasource_defaults(PROD))
        self.assertEqual(self.resolve(shipped=prospective), current)
        self.assertEqual(self.resolve(shipped=prospective, actual=prospective), current)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / ".env"; path.touch(mode=0o600)
            validate_deployment_configuration(container(), {}, path, prospective, BASE, current)
            path.write_text("SPRING_DATASOURCE_HIKARI_JDBC_URL=jdbc:postgresql://elsewhere/other\n")
            with self.assertRaises(BackupError):
                validate_deployment_configuration(container(), {}, path, prospective, BASE, current)

    def test_checked_in_production_configuration_supports_backup_resolution(self):
        prod = (ROOT / "src/main/resources/application-prod.yml").read_text()
        base = (ROOT / "src/main/resources/application.yml").read_text()
        self.resolve(shipped=prod, actual=prod, base=base)

    def test_hikari_yaml_rejects_connection_overrides_and_unsupported_pool_values(self):
        rejected = ["      maximum-pool-size: " + value + "\n" for value in
                    ("0", "-1", "1.5", "true", "'30'", '"30"', "${DB_POOL_MAX_SIZE:30}", "2147483648")]
        rejected += ["      " + key + ": other\n" for key in
                     ("jdbc-url", "username", "password", "data-source-class-name", "data-source-properties", "schema")]
        rejected += ["      maximum-pool-size: 30\n      maximum-pool-size: 31\n",
                     "      maximum-pool-size: 30\n    hikari:\n      maximum-pool-size: 30\n",
                     "      maximum-pool-size: 30\n      jdbc-url: other\n",
                     "      data-source-properties:\n        user: other\n", ""]
        for nested in rejected:
            prod = PROD.replace("  flyway:\n", "    hikari:\n" + nested + "  flyway:\n")
            with self.subTest(nested=nested), self.assertRaises(BackupError): datasource_defaults(prod)
            with self.subTest(nested=nested), self.assertRaises(BackupError): self.resolve(shipped=prod, actual=prod)
        for key in ("jdbc-url", "username", "password", "data-source-class-name", "data-source-properties.user"):
            prod = PROD + "spring.datasource.hikari." + key + ": other\n"
            with self.subTest(key=key), self.assertRaises(BackupError): validate_yaml_configuration(prod, prod=True)

    def test_unsupported_profiles_jvm_config_and_hikari_overrides_fail_closed(self):
        for entry in ("SPRING_PROFILES_ACTIVE=local", "JAVA_TOOL_OPTIONS=-Dspring.datasource.url=other",
                      "SPRING_APPLICATION_JSON={}", "SPRING_CONFIG_IMPORT=optional:file:/config",
                      "SPRING_FLYWAY_URL=other", "SPRING_DATASOURCE_HIKARI_JDBC_URL=other",
                      "SPRING_PROFILES_INCLUDE=tenant", "SPRING_FLYWAY_SCHEMAS=other", "SPRING_FLYWAY_TABLE=other",
                      "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_USER=other"):
            state = container(); state["Config"]["Env"] = [entry]
            with self.subTest(entry=entry), self.assertRaises(BackupError): self.resolve(state)
        state = container(); state["Args"].append("--spring.datasource.password=other")
        with self.assertRaises(BackupError): self.resolve(state)
        state = container(); state["Mounts"] = [{"Destination": "/app/config"}]
        with self.assertRaises(BackupError): self.resolve(state)
        with self.assertRaises(BackupError): self.resolve(properties=["spring.datasource.password=other"])

    def test_yaml_datasource_profile_and_external_config_overrides_fail_closed(self):
        for base in (BASE + "  config:\n    import: other\n", BASE + "  datasource:\n    url: other\n",
                     BASE + "  profiles:\n    include: other\n", BASE + "---\n" + BASE):
            with self.subTest(base=base), self.assertRaises(BackupError): self.resolve(base=base)
        for actual in (PROD + "  config:\n    import: other\n", PROD.replace("  flyway:\n", "  flyway:\n    schemas: other\n"),
                       PROD + "spring.config.import: other\n", PROD + "spring:\n  datasource: {url: other}\n"):
            with self.subTest(actual=actual), self.assertRaises(BackupError): self.resolve(actual=actual)

    def test_prospective_env_file_must_resolve_to_the_current_backup_database(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / ".env"
            current = self.resolve()
            path.touch(mode=0o600)
            validate_deployment_configuration(container(), {}, path, PROD, BASE, current)
            path.write_text("# Docker literal syntax\nDB_URL=jdbc:postgresql://db.internal:5432/mmdb\nDB_PASSWORD=test-only-secret\n")
            validate_deployment_configuration(container(), {}, path, PROD, BASE, current)
            for entry in ("DB_URL=jdbc:postgresql://elsewhere/mmdb", "DB_PASSWORD=other", "SPRING_PROFILES_ACTIVE=local",
                          "SPRING_FLYWAY_ENABLED=false", "DB_PASSWORD", "DB_PASSWORD=first\nDB_PASSWORD=second"):
                path.write_text(entry + "\n")
                with self.subTest(entry=entry), self.assertRaises(BackupError):
                    validate_deployment_configuration(container(), {}, path, PROD, BASE, current)
            path.write_text("\ufeff  # comment\nKEY='literal quoted value'\n")
            self.assertEqual(deployment_environment(path), {"KEY": "'literal quoted value'"})

    def test_prospective_hibernate_and_sql_initialization_cannot_bypass_flyway(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / ".env"; path.touch(mode=0o600)
            for entry in ("JPA_DDL_AUTO=create", "JPA_DDL_AUTO=create-drop", "JPA_DDL_AUTO=update",
                          "SPRING_JPA_HIBERNATE_DDL_AUTO=create", "SPRING_JPA_GENERATE_DDL=true",
                          "SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=update",
                          "SPRING_JPA_PROPERTIES_JAKARTA_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION=drop-and-create",
                          "SPRING_SQL_INIT_MODE=always", "SPRING_SQL_INIT_SCHEMA_LOCATIONS=classpath:other.sql",
                          "SPRING_DATASOURCE_INITIALIZATION_MODE=always"):
                path.write_text(entry + "\n")
                with self.subTest(entry=entry), self.assertRaises(BackupError):
                    validate_deployment_configuration(container(), {}, path, PROD, BASE, self.resolve())
            path.write_text("JPA_DDL_AUTO=validate\nSPRING_JPA_GENERATE_DDL=false\nSPRING_SQL_INIT_MODE=never\n")
            validate_deployment_configuration(container(), {}, path, PROD, BASE, self.resolve())

    def test_missing_container_visible_snapshot_cannot_hide_an_existing_host_env_file(self):
        with tempfile.TemporaryDirectory() as folder:
            host = Path(folder) / "host.env"; host.write_text("DB_URL=jdbc:postgresql://different/db\n"); os.chmod(host, 0o600)
            container_view = Path(folder) / "missing-in-container.env"
            marker = Path(folder) / "deployment-env-state.txt"; marker.write_text("present\n"); os.chmod(marker, 0o600)
            with self.assertRaises(BackupError): deployment_environment(container_view)
            with self.assertRaises((BackupError, FileNotFoundError)): validate_environment_snapshot(container_view, marker)
            container_view.touch(mode=0o600); marker.write_text("absent\n")
            self.assertEqual(validate_environment_snapshot(container_view, marker), "absent")
            container_view.write_text(host.read_text())
            with self.assertRaises(BackupError): validate_environment_snapshot(container_view, marker)
            marker.write_text("present\n")
            self.assertEqual(validate_environment_snapshot(container_view, marker), "present")
        wrapper = (ROOT / "scripts/predeploy_backup.sh").read_text()
        self.assertLess(wrapper.index("cp '$REMOTE_ENV_FILE'"), wrapper.index("runner=container"))
        self.assertIn('--deployment-env-file \\\"\\$taskdir/deployment.env\\\"', wrapper)
        deploy = (ROOT / ".gitlab-ci.yml").read_text().split("deploy:\n", 1)[1]
        self.assertNotIn("--env-file ${REMOTE_ENV_FILE}", deploy)
        self.assertIn('deployment.env; test -f', deploy)

    def test_invalid_urls_embedded_credentials_and_ssl_trust_are_rejected(self):
        for url in ("jdbc:mysql://host/db", "jdbc:postgresql:db", "jdbc:postgresql://first,second/db",
                    "jdbc:postgresql://user:pass@host/db", "jdbc:postgresql://host/other%20db",
                    "jdbc:postgresql://host/db?sslmode=verify-full", "jdbc:postgresql://host/db?currentSchema=other",
                    "jdbc:postgresql://host/db?sslmode", "jdbc:postgresql://host/db?sslmode=disable&sslmode=require"):
            with self.subTest(url=url), self.assertRaises(BackupError):
                parse_jdbc({"url": url, "username": "mmuser", "password": "private"})

    def test_pgpass_escapes_secrets_and_client_never_passes_password_in_arguments(self):
        connection = self.resolve(); connection["password"] = "private:back\\slash"
        self.assertIn("private\\:back\\\\slash", pgpass(connection))
        command = client_command("mm", "/tmp/backup", connection, "postgres:16-alpine", "pg_dump", "--format=custom")
        self.assertNotIn(connection["password"], " ".join(command))
        self.assertIn("PGPASSFILE=/backup/.pgpass", command)
        self.assertIn("container:mm", command)

    def test_private_directory_rejects_world_readable_and_symlink_locations(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder); os.chmod(path, 0o700); self.assertEqual(private_directory(path), path)
            os.chmod(path, 0o755)
            with self.assertRaises(BackupError): private_directory(path)
            os.chmod(path, 0o700); link = path / "link"; link.symlink_to(path, target_is_directory=True)
            with self.assertRaises(BackupError): private_directory(link)

    def test_preflight_checks_all_quantity_columns_and_rejects_changed_schema_or_permissions(self):
        columns = precision_columns((ROOT / "src/main/resources/db/migration/V6__stock_quantity_precision.sql").read_text())
        migrations = reviewed_migrations(ROOT / "src/main/resources/db/migration")
        self.assertEqual(len(columns), 164)
        self.assertEqual(validate_preflight(preflight(), self.resolve(), migrations), 16)
        self.assertEqual(validate_preflight(preflight(6), self.resolve(), migrations), 16)
        self.assertEqual(validate_preflight(preflight(7), self.resolve(), migrations), 16)
        for changes in ({"database": "other"}, {"owners_ok": False}, {"types_old_ok": False}, {"extra_views": 1},
                        {"columns": 163}, {"schema_create": False}, {"versions": ["1", "2", "3", "4", "5", "6"]}, {"failed": 1}):
            value = {**preflight(), **changes}
            with self.subTest(changes=changes), self.assertRaises(BackupError): validate_preflight(value, self.resolve(), migrations)
        changed = {**preflight(7), "types_new_ok": False, "types_old_ok": True}
        with self.assertRaises(BackupError): validate_preflight(changed, self.resolve(), migrations)

    def test_flyway_signed_crc32_ignores_only_initial_bom_and_line_endings(self):
        self.assertEqual(flyway_checksum(b"123456789"), -873187034)
        original = b"-- comment\nSELECT 1;\nSELECT 'unicode \xc3\xa9';\n"
        self.assertEqual(flyway_checksum(original), flyway_checksum(b"\xef\xbb\xbf" + original))
        self.assertEqual(flyway_checksum(original), flyway_checksum(original.replace(b"\n", b"\r\n")))
        self.assertEqual(flyway_checksum(original), flyway_checksum(original.replace(b"\n", b"\r")))
        self.assertNotEqual(flyway_checksum(original), flyway_checksum(original.replace(b"comment", b"different")))
        self.assertNotEqual(flyway_checksum(original), flyway_checksum(original.replace(b"SELECT 1", b"SELECT  1")))
        self.assertNotEqual(flyway_checksum(original), flyway_checksum(original + b"\xef\xbb\xbf"))

    def test_installed_history_requires_exact_reviewed_membership_identity_and_checksums(self):
        expected = reviewed_migrations(ROOT / "src/main/resources/db/migration")
        history = preflight()["history"]
        validate_history(history, expected)
        validate_history(preflight(7)["history"], expected)
        for field, value in (("version", "6"), ("script", "V1__other.sql"), ("checksum", None),
                             ("checksum", history[0]["checksum"] + 1), ("type", "BASELINE")):
            altered = copy.deepcopy(history); altered[0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(BackupError): validate_history(altered, expected)
        for altered in (None, history[:-1], list(reversed(history)), history + [history[0]], preflight(7)["history"] + [history[0]]):
            with self.subTest(altered=altered), self.assertRaises(BackupError): validate_history(altered, expected)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            for version in "123456": (path / ("V" + version + "__test.sql")).write_text("SELECT 1;")
            with self.assertRaises(BackupError): reviewed_migrations(path)
            (path / "V7__test.sql").write_text("SELECT 1;")
            self.assertEqual(set(reviewed_migrations(path)), set("1234567"))
            (path / "V1__duplicate.sql").write_text("SELECT 1;")
            with self.assertRaises(BackupError): reviewed_migrations(path)

    def simulated_backup(self, folder, *, fail_dump=False, wrong_catalog=False, wrong_jar=False, wrong_history=False, installed=5, shipped=PROD):
        config = folder / "expected.yml"; config.write_text(shipped)
        snapshot = folder / "prospective.env"; snapshot.touch(mode=0o600)
        state = folder / "deployment-env-state.txt"; state.write_text("absent\n"); os.chmod(state, 0o600)
        migration = ROOT / "src/main/resources/db/migration/V6__stock_quantity_precision.sql"
        operations = []
        def fake_run(args, operation):
            operations.append(args)
            if args[:2] == ["docker", "inspect"]: return json.dumps([container()])
            if args[:3] == ["docker", "image", "inspect"]: return json.dumps([{"Size": 1024}])
            if args[:2] == ["docker", "cp"]:
                with zipfile.ZipFile(args[-1], "w") as archive:
                    archive.writestr("BOOT-INF/classes/application.yml", BASE)
                    archive.writestr("BOOT-INF/classes/application-prod.yml", PROD)
                    for version, row in reviewed_migrations(migration.parent).items():
                        if int(version) > installed: continue
                        content = (migration.parent / row["script"]).read_bytes()
                        if wrong_jar: content += b"\n-- synthetic mismatch\n"
                        archive.writestr("BOOT-INF/classes/db/migration/" + row["script"], content)
                return ""
            if args[:3] == ["docker", "image", "save"]:
                Path(args[args.index("--output")+1]).write_bytes(b"rollback-image")
            if "psql" in args:
                result = preflight(installed)
                if wrong_history: result["history"][0]["checksum"] += 1
                return json.dumps(result)
            if "pg_dump" in args:
                self.assertEqual((folder / ".pgpass").stat().st_mode & 0o777, 0o600)
                if fail_dump: raise BackupError("Synthetic backup failure")
                (folder / "mm-before.dump").write_bytes(b"PGDMP-test-only")
            if "pg_restore" in args:
                return "header only" if wrong_catalog else "; archive\nTABLE public companies\nTABLE public flyway_schema_history\n"
            return ""
        stream = io.StringIO()
        with patch("predeploy_backup.run", side_effect=fake_run), patch("predeploy_backup.shutil.disk_usage", return_value=type("Usage", (), {"free": 10**12})()), contextlib.redirect_stdout(stream):
            backup("mm", folder, config, migration, snapshot, state)
        return operations, stream.getvalue()

    def test_successful_backup_is_private_verified_and_does_not_stop_backend(self):
        with tempfile.TemporaryDirectory() as name:
            folder = Path(name); operations, output = self.simulated_backup(folder)
            marker = json.loads((folder / "backup-verified.json").read_text())
            self.assertEqual(marker["sha256"], checksum(folder / "mm-before.dump"))
            self.assertIn("test restore was not performed", marker["verification"])
            self.assertEqual((folder / "container-inspect.json").stat().st_mode & 0o777, 0o600)
            self.assertFalse((folder / ".pgpass").exists()); self.assertFalse((folder / ".runtime-app.jar").exists())
            self.assertTrue(any("postgres:16-alpine" in command and "pg_dump" in command for command in operations))
            self.assertTrue(all("stop" not in command and "rm" not in command for command in operations))
            self.assertNotIn("test-only-secret", output); self.assertNotIn("db.internal", output)

    def test_backup_accepts_pool_increase_from_legacy_running_jar(self):
        prospective = PROD.replace("  flyway:\n", "    hikari:\n      maximum-pool-size: 30\n  flyway:\n")
        with tempfile.TemporaryDirectory() as name:
            folder = Path(name)
            operations, output = self.simulated_backup(folder, shipped=prospective)
            self.assertTrue((folder / "backup-verified.json").is_file())
            self.assertTrue(all("stop" not in command and "rm" not in command for command in operations))
            self.assertNotIn("test-only-secret", output)

    def test_dump_or_archive_failure_never_creates_verified_marker_and_cleans_credentials(self):
        for failure in ({"fail_dump": True}, {"wrong_catalog": True}, {"wrong_jar": True}, {"wrong_history": True}):
            with tempfile.TemporaryDirectory() as name:
                folder = Path(name)
                with self.subTest(failure=failure), self.assertRaises(BackupError): self.simulated_backup(folder, **failure)
                self.assertFalse((folder / "backup-verified.json").exists())
                self.assertFalse((folder / ".pgpass").exists())

    def test_v7_normal_redeployment_is_backed_up_and_v7_checksum_mismatch_rejects(self):
        with tempfile.TemporaryDirectory() as name:
            folder = Path(name)
            self.simulated_backup(folder, installed=7)
            self.assertTrue((folder / "backup-verified.json").exists())
            self.assertEqual(json.loads((folder / "database-preflight.json").read_text())["versions"], list("1234567"))
        with tempfile.TemporaryDirectory() as name:
            folder = Path(name)
            with self.assertRaises(BackupError): self.simulated_backup(folder, installed=7, wrong_history=True)
            self.assertFalse((folder / "backup-verified.json").exists())

    def test_wrong_deployed_image_fails_immediately_without_automatic_rollback(self):
        state = container(); state["Config"]["Image"] = "repo:latest"
        with patch("predeploy_backup.run", return_value=json.dumps([state])) as runner:
            with self.assertRaises(BackupError): health("mm", "repo:" + "a" * 40, "http://127.0.0.1:10320/actuator/health/deployment", 1)
            self.assertEqual(runner.call_count, 1)
            self.assertEqual(runner.call_args.args[0], ["docker", "inspect", "mm"])

    def test_health_requires_reviewed_image_and_local_up_response_without_redirects(self):
        image = "repo:" + "a" * 40
        state = container(); state["Config"]["Image"] = image
        class Response(io.BytesIO):
            status = 200
            def geturl(self): return "http://127.0.0.1:10320/actuator/health/deployment"
        class Opener:
            def open(self, url, timeout): return Response(b'{"status":"UP"}')
        output = io.StringIO()
        with patch("predeploy_backup.run", return_value=json.dumps([state])), patch("predeploy_backup.request.build_opener", return_value=Opener()), contextlib.redirect_stdout(output):
            health("mm", image, "http://127.0.0.1:10320/actuator/health/deployment", 1)
        self.assertIn("healthy after migration", output.getvalue())
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "redirect", {}, "http://other"))

    def test_pipeline_backups_before_stop_and_uses_immutable_image(self):
        pipeline = (ROOT / ".gitlab-ci.yml").read_text()
        self.assertLess(pipeline.index("predeploy_backup.sh backup"), pipeline.index("docker stop"))
        self.assertGreater(pipeline.index("predeploy_backup.sh health"), pipeline.index("docker run -d"))
        self.assertIn('docker push "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"', pipeline)
        self.assertIn("resource_group: mm-backend-production", pipeline)
        deploy = pipeline[pipeline.index("deploy:\n"):]
        self.assertNotIn("${CI_REGISTRY_IMAGE}:latest", deploy)


if __name__ == "__main__": unittest.main()
