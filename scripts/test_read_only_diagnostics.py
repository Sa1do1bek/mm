import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import read_only_diagnostics as diagnostics


STATE = {"status": "running", "running": True, "restarting": False, "oomKilled": False,
         "restartCount": 2, "imageId": "sha256:" + "a" * 64,
         "image": "private-registry/mm:" + "b" * 40, "revision": "b" * 40,
         "startedAt": "2026-10-09T12:00:00.123456789Z", "finishedAt": "0001-01-01T00:00:00Z"}
STATS = {"cpuPercent": "12.30%", "memory": "418.5MiB / 7.77GiB", "memoryPercent": "5.26%", "pids": "42"}
TIMING = {"httpStatus": "200", "connectSeconds": 0.09, "tlsSeconds": 0.12,
          "firstByteSeconds": 0.25, "totalSeconds": 0.26}
SECRET = "private-password-and-business-payload-must-not-be-printed"
DATABASE_CONFIG = {"image": "private-registry/mm:" + diagnostics.REVIEWED_DATABASE_IMAGE,
                   "expectedCommand": True, "unsupportedOverrides": False, "workingDir": "/app",
                   "env": [], "mounts": []}


class ReadOnlyDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.key = Path(self.folder.name) / "key"
        self.key.write_text("test-only-placeholder")
        self.key.chmod(0o600)
        self.env = {"MM_READ_ONLY_DIAGNOSTICS": "1", "CI_COMMIT_BRANCH": "main", "CI_DEFAULT_BRANCH": "main",
                    "CI_COMMIT_REF_PROTECTED": "true", "CI_PIPELINE_SOURCE": "web", "CI_DEBUG_TRACE": "false",
                    "SERVICE_NAME": diagnostics.CONTAINER, "SSH_PRIVATE_KEY": str(self.key),
                    "SERVER_HOST_TENZOR": "diagnostic.example", "SSH_USER": "deploy", "SSH_PORT_TENZOR": "22"}

    def test_only_explicit_protected_main_diagnostics_can_prepare_ssh(self):
        arguments = diagnostics.ssh_arguments(self.env)
        self.assertEqual(arguments[-1], "deploy@diagnostic.example")
        for field, value in (("MM_READ_ONLY_DIAGNOSTICS", "0"), ("CI_COMMIT_BRANCH", "feature"),
                             ("CI_COMMIT_REF_PROTECTED", "false"), ("CI_PIPELINE_SOURCE", "push"),
                             ("CI_DEBUG_TRACE", "true"), ("SERVICE_NAME", "another-service"),
                             ("SERVER_HOST_TENZOR", "host; dangerous-command")):
            with self.subTest(field=field), self.assertRaises(diagnostics.DiagnosticError):
                diagnostics.ssh_arguments(self.env | {field: value})
        self.key.chmod(0o644)
        with self.assertRaises(diagnostics.DiagnosticError): diagnostics.ssh_arguments(self.env)

    def test_container_image_and_state_output_omit_unrecognized_values(self):
        result = diagnostics.container_state(json.dumps(STATE | {"image": SECRET, "revision": SECRET, "Env": [SECRET]}))
        self.assertEqual(result["imageRevisionTag"], "UNEXPECTED")
        self.assertEqual(result["imageRevisionLabel"], "UNSET")
        self.assertNotIn(SECRET, json.dumps(result))

    def test_exception_output_contains_types_and_counts_without_raw_messages(self):
        logs = "HikariPool-1 - Connection is not available: " + SECRET + "\n" \
            + "java.sql.SQLTransientConnectionException: " + SECRET + "\n" \
            + "org.springframework.transaction.CannotCreateTransactionException: " + SECRET + "\n" \
            + "BusinessAccountSecretException: " + SECRET
        result = diagnostics.exception_counts(logs)
        self.assertEqual(result["allowlistedExceptionMentions"]["SQLTransientConnectionException"], 1)
        self.assertEqual(result["allowlistedExceptionMentions"]["CannotCreateTransactionException"], 1)
        self.assertEqual(result["hikariConnectionUnavailableMentions"], 1)
        self.assertEqual(result["otherExceptionMentions"], 1)
        self.assertNotIn(SECRET, json.dumps(result))
        self.assertNotIn("BusinessAccountSecretException", json.dumps(result))

    def test_migration_phase_is_reported_without_database_or_raw_log_details(self):
        started = diagnostics.exception_counts('Migrating schema "private-schema" to version "9 - warehouse imported movement projection" ' + SECRET)
        self.assertTrue(started["projectionMigrationStarted"])
        self.assertFalse(started["projectionMigrationFinished"])
        finished = diagnostics.exception_counts('Successfully applied 1 migration to schema "private-schema", now at version v9 (execution time 00:30.200s)\nStarted MmBackendApplication in 38.1 seconds ' + SECRET)
        self.assertTrue(finished["projectionMigrationFinished"])
        self.assertTrue(finished["applicationStarted"])
        failure = diagnostics.exception_counts('SQL State  : 23505\nMessage : ' + SECRET)
        self.assertEqual(failure["sqlStates"], ["23505"])
        self.assertNotIn(SECRET, json.dumps([started, finished, failure]))
        self.assertNotIn("private-schema", json.dumps([started, finished]))

    def test_collection_uses_only_bounded_fixed_read_probes(self):
        calls = []
        def runner(arguments, **options):
            calls.append((arguments[-1], options))
            command = arguments[-1]
            if command.startswith("docker inspect"):
                output = json.dumps(STATE)
            elif command.startswith("docker stats"):
                output = json.dumps(STATS)
            elif command.startswith("df "):
                output = "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk 100000 75000 25000 75% /\n"
            elif "loadavg" in command:
                output = "1.20 0.80 0.40 2/300 4321\n"
            elif "meminfo" in command:
                output = "MemTotal: 100000 kB\nMemAvailable: 20000 kB\nSwapTotal: 10000 kB\nSwapFree: 5000 kB\n"
            elif command == "hostname -I":
                output = "192.168.1.27 10.20.30.1 fe80::1"
            elif command == diagnostics.DOCKER_NETWORK_COMMAND:
                output = json.dumps({"private-network": {"IPAddress": "172.18.0.3", "IPAMConfig": None}})
            elif command == "pg_lsclusters":
                output = "Ver Cluster Port Status Owner Data directory Log file\n17 main 5432 down postgres /private/data /private/log\n"
            elif command == diagnostics.POSTGRES_UNITS_COMMAND:
                output = "postgresql@17-main.service loaded failed failed PostgreSQL cluster\n"
            elif command == diagnostics.POSTGRES_UNIT_FILES_COMMAND:
                output = "postgresql@17-main.service enabled enabled\n"
            elif command == diagnostics.POSTGRES_CONTAINER_COMMAND:
                output = ""
            elif command.startswith("bash "):
                output = "java.sql.SQLTransientConnectionException: " + SECRET
            elif command.startswith("timeout "):
                output = ""
            else:
                output = '{"status":"UP","details":"' + SECRET + '"}' + diagnostics.TIMING_MARKER + json.dumps(TIMING)
            if command.startswith("docker inspect") and "workingDir" in command:
                output = json.dumps(DATABASE_CONFIG)
            return subprocess.CompletedProcess(arguments, 0, output, SECRET)
        report = diagnostics.collect(["ssh", "test-only"], runner)
        self.assertEqual(len(calls), 19)
        self.assertEqual(report["persistentRemoteMutations"], 0)
        self.assertEqual(report["databaseMutations"], 0)
        self.assertEqual(report["probes"]["localhostHealth"]["data"]["healthStatus"], "UP")
        self.assertEqual(report["probes"]["publicHealth"]["data"]["httpStatus"], "200")
        self.assertNotIn(SECRET, json.dumps(report))
        for command, options in calls:
            self.assertIn(options["timeout"], (12, 15, 50))
            self.assertTrue(options["capture_output"])
            self.assertNotRegex(command, r"docker (?:run|stop|restart|rm|pull)\b")
            if command.startswith("docker exec"):
                self.assertTrue(command.startswith("docker exec " + diagnostics.CONTAINER + " timeout --signal=TERM --kill-after=1s 5s bash -c "))
                self.assertIn("/dev/tcp/", command)
        health_commands = [command for command, _ in calls if command.startswith("curl ")]
        self.assertEqual(len(health_commands), 2)
        for command in health_commands:
            self.assertIn("--max-time 8", command)
            self.assertNotIn("--location", command)
            self.assertNotIn("--insecure", command)
        self.assertEqual(report["probes"]["databaseTcp"]["serverHost"]["connectionOutcome"], "CONNECTED")

    def test_database_host_location_returns_only_locality_without_addresses(self):
        for addresses, expected in (("192.168.1.27 10.20.30.1 fe80::1", True), ("10.20.30.1 fe80::1", False)):
            result = diagnostics.database_host_location(addresses)
            self.assertEqual(result["reviewedDatabaseHostIsLocal"], expected)
            self.assertFalse(result["hostAddressesPrinted"])
            self.assertNotIn("192.168", json.dumps(result))
            self.assertNotIn("10.20", json.dumps(result))
            self.assertNotIn("fe80", json.dumps(result))
        with self.assertRaises(ValueError): diagnostics.database_host_location(SECRET)

    def test_database_docker_location_matches_running_or_stopped_addresses_without_disclosure(self):
        for networks, expected in (({"IPAddress": "192.168.1.27"}, True),
                                   ({"IPAddress": "", "IPAMConfig": {"IPv4Address": "192.168.1.27"}}, True),
                                   ({"IPAddress": "172.18.0.3", "GlobalIPv6Address": "fe80::1"}, False)):
            output = json.dumps({SECRET: networks | {"Aliases": [SECRET]}})
            result = diagnostics.database_docker_location(output)
            self.assertEqual(result["reviewedDatabaseTargetMatchesDockerNetwork"], expected)
            self.assertFalse(result["containerAddressesPrinted"])
            self.assertNotIn(SECRET, json.dumps(result))
            self.assertNotIn("192.168", json.dumps(result))
            self.assertNotIn("172.18", json.dumps(result))
        with self.assertRaises(diagnostics.DiagnosticError): diagnostics.database_docker_location("[]")

    def test_postgres_service_inventory_filters_port_and_redacts_owners_paths_descriptions(self):
        clusters = diagnostics.postgres_clusters("Ver Cluster Port Status Owner Data directory Log file\n"
            + "17 main 5432 down " + SECRET + " /private/data /private/log\n"
            + "16 unrelated 5433 online " + SECRET + " /private/data /private/log\n")
        self.assertEqual(clusters["clusters"], [{"version": "17", "cluster": "main", "status": "down", "configuredPortMatches": True}])
        units = diagnostics.postgres_units("postgresql@17-main.service loaded failed failed " + SECRET)
        self.assertEqual(units["units"][0]["activeState"], "failed")
        self.assertNotIn(SECRET, json.dumps([clusters, units]))
        self.assertNotIn("/private", json.dumps(clusters))
        self.assertNotIn("unrelated", json.dumps(clusters))

    def test_postgres_container_inventory_returns_only_target_published_service(self):
        database = {"id": "a" * 64, "name": "/postgres-main", "image": "postgres:17", "status": "exited", "oomKilled": True,
                    "exitCode": 137, "restartCount": 0, "restartPolicy": "no",
                    "portBindings": {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]}}
        unrelated = database | {"name": "/" + SECRET, "portBindings": {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5433"}]}}
        non_database = unrelated | {"image": "private-registry/backend:main"}
        result = diagnostics.postgres_containers("\n".join(json.dumps(value) for value in (database, unrelated, non_database)))
        self.assertEqual(len(result["containers"]), 1)
        self.assertTrue(result["containers"][0]["oomKilled"])
        self.assertNotIn(SECRET, json.dumps(result))
        self.assertNotIn("0.0.0.0", json.dumps(result))
        self.assertNotIn("postgres:17", json.dumps(result))
        custom = database | {"image": "private-registry/custom-db:main", "postgresCommand": True}
        self.assertEqual(diagnostics.postgres_containers(json.dumps(custom))["containers"][0]["serviceKind"], "POSTGRESQL")
        unknown = custom | {"postgresCommand": False}
        self.assertEqual(diagnostics.postgres_containers(json.dumps(unknown))["containers"][0]["serviceKind"], "UNRECOGNIZED")

    def test_systemd_inventory_accepts_native_versions_failed_markers_and_empty_banners(self):
        output = ("● postgresql-16.service loaded failed failed " + SECRET + "\n"
                  + "pgsql.service loaded inactive dead " + SECRET + "\n"
                  + "2 loaded units listed.\n")
        result = diagnostics.postgres_units(output)
        self.assertEqual([row["unit"] for row in result["units"]], ["postgresql-16.service", "pgsql.service"])
        self.assertEqual(result["unrecognizedLineCount"], 0)
        empty = diagnostics.postgres_units("0 loaded units listed.\nTo show all installed unit files use 'systemctl list-unit-files'.\n")
        self.assertEqual(empty["units"], [])
        self.assertEqual(empty["unrecognizedLineCount"], 0)
        self.assertNotIn(SECRET, json.dumps(result))

    def test_systemd_inventory_retains_valid_units_without_printing_rejected_lines(self):
        result = diagnostics.postgres_units("postgresql@17-main.service loaded inactive dead " + SECRET
                                            + "\n" + SECRET + "\npostgresql-exporter.service loaded active running " + SECRET)
        self.assertEqual(len(result["units"]), 1)
        self.assertEqual(result["unrecognizedLineCount"], 2)
        self.assertEqual(result["parserErrorCategory"], "UNRECOGNIZED_SYSTEMD_LINES")
        self.assertNotIn(SECRET, json.dumps(result))
        self.assertNotIn("exporter", json.dumps(result))

    def test_installed_postgres_unit_inventory_is_bounded_and_excludes_descriptions(self):
        result = diagnostics.postgres_unit_files("postgresql-16.service enabled enabled\npgsql.service disabled\n"
                                                 + "postgresql-exporter.service enabled disabled\n" + SECRET)
        self.assertEqual(result["unitFiles"], [{"unit": "postgresql-16.service", "unitFileState": "enabled", "preset": "enabled"},
                                               {"unit": "pgsql.service", "unitFileState": "disabled"}])
        self.assertEqual(result["unrecognizedLineCount"], 2)
        self.assertNotIn(SECRET, json.dumps(result))
        with self.assertRaises(diagnostics.DiagnosticError):
            diagnostics.postgres_unit_files("x" * 65537)

    def test_host_network_postgres_candidate_does_not_claim_an_unverified_port(self):
        database = {"id": "c" * 64, "name": "/postgres-host", "image": "private/custom:tag", "status": "exited",
                    "oomKilled": True, "exitCode": 137, "restartCount": 0, "restartPolicy": "unless-stopped",
                    "portBindings": {}, "postgresCommand": True, "usesHostNetwork": True}
        unrelated = database | {"name": "/" + SECRET, "postgresCommand": False}
        bridged = database | {"name": "/" + SECRET, "usesHostNetwork": False}
        result = diagnostics.postgres_containers("\n".join(json.dumps(row) for row in (database, unrelated, bridged)))
        self.assertEqual(len(result["containers"]), 1)
        candidate = result["containers"][0]
        self.assertEqual(candidate["serviceKind"], "POSTGRESQL")
        self.assertTrue(candidate["usesHostNetwork"])
        self.assertTrue(candidate["requiresPortVerification"])
        self.assertFalse(candidate["configuredPortMatches"])
        self.assertNotIn(SECRET, json.dumps(result))
        self.assertNotIn("private/custom", json.dumps(result))

    def test_http_timeout_preserves_timings_without_ssh_or_body_disclosure(self):
        timeout = TIMING | {"httpStatus": "000", "firstByteSeconds": 0, "totalSeconds": 8}
        def runner(arguments, **options):
            return subprocess.CompletedProcess(arguments, 28, SECRET + diagnostics.TIMING_MARKER + json.dumps(timeout), SECRET)
        report = diagnostics.collect(["ssh"], runner)
        self.assertEqual(report["probes"]["publicHealth"]["data"]["httpStatus"], "000")
        self.assertEqual(report["probes"]["publicHealth"]["data"]["healthStatus"], "NO_VALID_HEALTH_BODY")
        self.assertFalse(report["probes"]["recentExceptionCounts"]["responseValidated"])
        self.assertNotIn(SECRET, json.dumps(report))

    def test_ssh_failures_identify_transport_or_auth_without_disclosing_stderr(self):
        failures = {
            "Connection timed out": "TIMEOUT",
            "Connection refused": "CONNECTION_REFUSED",
            "No route to host": "NETWORK_UNREACHABLE",
            "Could not resolve hostname": "DNS_FAILED",
            "Permission denied (publickey)": "AUTHENTICATION_FAILED",
            'Load key "/private/key": invalid format': "KEY_UNAVAILABLE",
            "Host key verification failed": "HOST_KEY_FAILED",
            "kex_exchange_identification: Connection closed": "SSH_HANDSHAKE_FAILED",
            SECRET: "UNCLASSIFIED_SSH_FAILURE",
        }
        for message, expected in failures.items():
            with self.subTest(expected=expected):
                def runner(arguments, **options):
                    return subprocess.CompletedProcess(arguments, 255, SECRET, message + " " + SECRET)
                report = diagnostics.collect(["ssh"], runner)
                for name in ("containerState", "hostLoad", "localhostHealth", "publicHealth"):
                    record = report["probes"][name]
                    self.assertEqual(record["transportError"], expected)
                    self.assertFalse(record["responseValidated"])
                    self.assertGreaterEqual(record["elapsedSeconds"], 0)
                self.assertNotIn(SECRET, json.dumps(report))
                self.assertNotIn("/private/key", json.dumps(report))
    def test_transport_failures_are_fixed_labels_and_not_false_zero_log_counts(self):
        def runner(arguments, **options):
            raise subprocess.TimeoutExpired(arguments, 15, output=SECRET, stderr=SECRET)
        report = diagnostics.collect(["ssh"], runner)
        self.assertTrue(all(probe.get("transportError") == "TIMEOUT" and "data" not in probe
                            for name, probe in report["probes"].items() if name != "databaseTcp"))
        self.assertEqual(report["probes"]["databaseTcp"]["transportError"], "TIMEOUT")
        self.assertNotIn(SECRET, json.dumps(report))
        def denied(arguments, **options):
            return subprocess.CompletedProcess(arguments, 255, "", SECRET)
        report = diagnostics.collect(["ssh"], denied)
        self.assertNotIn("data", report["probes"]["recentExceptionCounts"])

    def test_ci_flag_disables_every_mutating_job_and_diagnostics_are_opt_in(self):
        pipeline = (Path(__file__).resolve().parents[1] / ".gitlab-ci.yml").read_text()
        for job in ("compile", "build", "deploy"):
            section = pipeline.split("\n" + job + ":\n", 1)[1].split("\n\n", 1)[0]
            self.assertIn("if: '$MM_READ_ONLY_DIAGNOSTICS == \"1\"'\n      when: never", section)
            self.assertIn("- when: on_success", section)
        section = pipeline.split("\nread_only_diagnostics:\n", 1)[1]
        self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', section)
        self.assertIn('$CI_PIPELINE_SOURCE == "web"', section)
        self.assertIn('$CI_PIPELINE_SOURCE == "api"', section)
        self.assertIn('- when: never', section)
        self.assertNotIn('predeploy_backup.sh', section)
        self.assertNotIn('docker run', section)
        self.assertIn('needs: [diagnostic_tests]',section)
        self.assertIn('python3 scripts/guard_diagnostic_commit.py',pipeline)
        self.assertIn('python3 -m unittest discover',pipeline)
        self.assertIn('$CI_COMMIT_TITLE =~ /^\\[mm-diagnostics\\]/',pipeline)

    def test_hikari_latest_numeric_state_and_fixed_failure_phrase_counts(self):
        logs = ("HikariPool-1 - Pool stats (total=20, active=1, idle=19, waiting=0)\n"
                "HikariPool-1 - Connection is not available (total=20, active=20, idle=0, waiting=8): " + SECRET + "\n"
                "Connection refused; Connection attempt timed out; Network is unreachable; "
                "Too many clients already; password authentication failed: " + SECRET + "\n"
                "CustomerSecret (total=99, active=99, idle=0, waiting=99)\n")
        result = diagnostics.exception_counts(logs)
        self.assertEqual(result["latestHikariPoolState"], {"total": 20, "active": 20, "idle": 0, "waiting": 8})
        self.assertTrue(all(value == 1 for value in result["failurePhraseMentions"].values()))
        self.assertIsNone(diagnostics.exception_counts("no numeric pool data")["latestHikariPoolState"])
        self.assertNotIn(SECRET, json.dumps(result))

    def test_private_jdbc_target_precedence_default_and_unknown_runtime_rejection(self):
        self.assertEqual(diagnostics.database_target(json.dumps(DATABASE_CONFIG)),
                         (*diagnostics.REVIEWED_DATABASE_TARGET, "REVIEWED_SHIPPED_DEFAULT"))
        overrides = DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://ignored.example:1111/db",
                                               "SPRING_DATASOURCE_URL=jdbc:postgresql://database.example:6543/db"]}
        self.assertEqual(diagnostics.database_target(json.dumps(overrides)), ("database.example", 6543, "SPRING_DATASOURCE_URL"))
        for config in (DATABASE_CONFIG | {"image": "different-image:latest"},
                       DATABASE_CONFIG | {"expectedCommand": False},
                       DATABASE_CONFIG | {"mounts": [{"Destination": "/app/config/prod.yml"}]},
                       DATABASE_CONFIG | {"env": ["JAVA_TOOL_OPTIONS=-Dspring.datasource.url=other"]},
                       DATABASE_CONFIG | {"env": ["SPRING_PROFILES_ACTIVE=dev"]},
                       DATABASE_CONFIG | {"env": ["SPRING_CONFIG_LOCATION=secret"]},
                       DATABASE_CONFIG | {"env": ["SPRING_DATASOURCE_HIKARI_JDBC_URL=other"]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://safe.example;touch-bad/db"]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://host,other/db"]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://user:password@host/db"]},
                       DATABASE_CONFIG | {"env": ["SPRING_DATASOURCE_URL="]},
                       DATABASE_CONFIG | {"unsupportedOverrides": True},
                       DATABASE_CONFIG | {"env": ["DB_PASSWORD=" + SECRET]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://host:0/db"]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://host/db?PGHOST=other"]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://host/db?PGPORT=1234"]},
                       DATABASE_CONFIG | {"env": ["DB_URL=jdbc:postgresql://host/db?password=" + SECRET]}):
            with self.subTest(config=config), self.assertRaises((diagnostics.DiagnosticError, ValueError)):
                diagnostics.database_target(json.dumps(config))

    def test_tcp_probe_bounded_sanitized_outcomes_on_both_routes(self):
        for code, stderr, expected in ((0, SECRET, "CONNECTED"), (1, "Connection refused " + SECRET, "REFUSED"),
                                       (124, SECRET, "TIMED_OUT"), (1, "No route to host " + SECRET, "UNREACHABLE"),
                                       (1, "Name or service not known " + SECRET, "DNS_FAILURE"), (127, SECRET, "FAILED")):
            calls = []
            def runner(arguments, **options):
                calls.append((arguments[-1], options))
                if arguments[-1].startswith("docker inspect"):
                    return subprocess.CompletedProcess(arguments, 0, json.dumps(DATABASE_CONFIG), SECRET)
                return subprocess.CompletedProcess(arguments, code, SECRET, stderr)
            with self.subTest(expected=expected):
                record = diagnostics.database_tcp_probe(["ssh"], runner)
                for key, origin in (("serverHost", "SERVER_HOST_NETWORK"), ("backendContainer", "BACKEND_CONTAINER_NETWORK")):
                    self.assertEqual(record[key]["connectionOutcome"], expected)
                    self.assertEqual(record[key]["probeOrigin"], origin)
                self.assertFalse(record["checksPostgresqlHealth"])
                self.assertFalse(record["targetPrinted"])
                self.assertEqual(len(calls), 3)
                self.assertIn("timeout --signal=TERM --kill-after=1s 5s", calls[1][0])
                self.assertTrue(calls[2][0].startswith("docker exec " + diagnostics.CONTAINER + " " + calls[1][0]))
                self.assertTrue(all(options["timeout"] == 12 and options["capture_output"] for _, options in calls))
                self.assertNotIn(SECRET, json.dumps(record))
                self.assertNotIn(diagnostics.REVIEWED_DATABASE_TARGET[0], json.dumps(record))

    def test_invalid_private_database_configuration_never_opens_socket(self):
        calls = []
        def runner(arguments, **options):
            calls.append(arguments)
            return subprocess.CompletedProcess(arguments, 0, json.dumps(DATABASE_CONFIG | {"image": SECRET}), SECRET)
        record = diagnostics.database_tcp_probe(["ssh"], runner)
        self.assertFalse(record["responseValidated"])
        self.assertEqual(len(calls), 1)
        self.assertNotIn(SECRET, json.dumps(record))


if __name__ == "__main__":
    unittest.main()
