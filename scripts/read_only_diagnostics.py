#!/usr/bin/env python3
"""Protected-main CI diagnostics: fixed read-only SSH probes, sanitized output only."""
import json
import hashlib
import ipaddress
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit

CONTAINER = "materials-management-backend"
HEALTH_URLS = {
    "localhostHealth": "http://127.0.0.1:10320/actuator/health/deployment",
    "publicHealth": "https://api-navoiy-azot-mm.tenzorsoft.uz/actuator/health/deployment",
}
EXCEPTIONS = {
    "SQLTransientConnectionException", "SQLTimeoutException", "SQLException", "PSQLException",
    "CannotCreateTransactionException", "CannotGetJdbcConnectionException", "JDBCConnectionException",
    "DataAccessResourceFailureException", "TransientDataAccessResourceException", "SocketTimeoutException",
    "ConnectException", "OutOfMemoryError", "IllegalStateException", "RuntimeException",
}
INSPECT_FORMAT = '{"status":{{json .State.Status}},"running":{{json .State.Running}},"restarting":{{json .State.Restarting}},"oomKilled":{{json .State.OOMKilled}},"restartCount":{{json .RestartCount}},"startedAt":{{json .State.StartedAt}},"finishedAt":{{json .State.FinishedAt}},"imageId":{{json .Image}},"image":{{json .Config.Image}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}}}'
STATS_FORMAT = '{"cpuPercent":{{json .CPUPerc}},"memory":{{json .MemUsage}},"memoryPercent":{{json .MemPerc}},"pids":{{json .PIDs}}}'
TIMING_MARKER = "\nMM_DIAGNOSTIC_TIMING="
DOCKER_NETWORK_COMMAND = "sh -c " + shlex.quote(
    'set -eu; mm_container_ids=$(docker ps -aq); if [ -n "$mm_container_ids" ]; then '
    'docker inspect --format \'{{json .NetworkSettings.Networks}}\' $mm_container_ids; fi'
)
POSTGRES_CONTAINER_FORMAT = ('{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Config.Image}},'
    '"status":{{json .State.Status}},"oomKilled":{{json .State.OOMKilled}},"exitCode":{{json .State.ExitCode}},'
    '"restartCount":{{json .RestartCount}},"portBindings":{{json .HostConfig.PortBindings}},'
    '"restartPolicy":{{json .HostConfig.RestartPolicy.Name}},'
    '"usesHostNetwork":{{if eq .HostConfig.NetworkMode "host"}}true{{else}}false{{end}},'
    '"postgresCommand":{{if and .Config.Cmd (eq (index .Config.Cmd 0) "postgres")}}true{{else}}false{{end}}}')
POSTGRES_CONTAINER_COMMAND = "sh -c " + shlex.quote(
    'set -eu; mm_container_ids=$(docker ps -aq); if [ -n "$mm_container_ids" ]; then '
    'docker inspect --format ' + shlex.quote(POSTGRES_CONTAINER_FORMAT) + ' $mm_container_ids; fi'
)
SYSTEMD_READ_PREFIX = "env LC_ALL=C SYSTEMD_COLORS=0 SYSTEMD_URLIFY=0 systemctl "
POSTGRES_UNITS_COMMAND = SYSTEMD_READ_PREFIX + "list-units --all --type=service --plain --no-legend --no-pager 'postgres*.service' 'pgsql*.service'"
POSTGRES_UNIT_FILES_COMMAND = SYSTEMD_READ_PREFIX + "list-unit-files --type=service --no-legend --no-pager 'postgres*.service' 'pgsql*.service'"
POSTGRES_UNIT_PATTERN = r"(?:postgresql|postgres|pgsql)(?:-\d+(?:\.\d+)?|@[A-Za-z0-9_.-]+)?\.service"
# The diagnostic-only pipeline does not replace this reviewed running image.
REVIEWED_DATABASE_IMAGE = "510d25351d2ddc5d94626c40be8cbfa2e9a1632e"
REVIEWED_DATABASE_TARGET = ("192.168.1.27", 5432)
TARGET_ENV_KEYS = ("DB_URL", "SPRING_DATASOURCE_URL", "SPRING_PROFILES_ACTIVE",
                   "SPRING_PROFILES_DEFAULT", "SPRING_DATASOURCE_DRIVER_CLASS_NAME")
BLOCKED_ENV_KEYS = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS",
                    "SPRING_APPLICATION_JSON", "SPRING_PROFILES_INCLUDE", "SPRING_DATASOURCE_JNDI_NAME",
                    "SPRING_DATASOURCE_HIKARI_JDBC_URL", "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_CLASS_NAME")
_target_filter = "or " + " ".join('(eq $key "' + key + '")' for key in TARGET_ENV_KEYS)
_blocked_filter = "or " + " ".join('(eq $key "' + key + '")' for key in BLOCKED_ENV_KEYS)
for _prefix in ("SPRING_CONFIG_", "SPRING_PROFILES_GROUP_", "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_"):
    _blocked_filter += ' (eq (printf "%.' + str(len(_prefix)) + 's" $key) "' + _prefix + '") '
# Filtering happens on the server: password environment values never reach CI.
DATABASE_INSPECT_FORMAT = (
    '{{$blocked := false}}{{range .Config.Env}}{{$key := index (split . "=") 0}}'
    '{{if and (ne . (printf "%s=" $key)) (' + _blocked_filter + ')}}{{$blocked = true}}{{end}}{{end}}'
    '{"image":{{json .Config.Image}},"expectedCommand":{{if and (eq .Path "java") '
    '(eq (join .Args " ") "-jar app.jar")}}true{{else}}false{{end}},'
    '"workingDir":{{json .Config.WorkingDir}},"unsupportedOverrides":{{$blocked}},"env":['
    '{{$first := true}}{{range .Config.Env}}{{$key := index (split . "=") 0}}{{if ' + _target_filter + '}}'
    '{{if not $first}},{{end}}{{json .}}{{$first = false}}{{end}}{{end}}],"mounts":['
    '{{$first := true}}{{range .Mounts}}{{if not $first}},{{end}}'
    '{"Destination":{{json .Destination}}}{{$first = false}}{{end}}]}'
)

FAILURE_PHRASES = {
    "connectionRefused": r"connection refused",
    "connectionTimedOut": r"(?:connection|connect)(?: attempt)? (?:timed out|timeout)",
    "networkUnreachable": r"network is unreachable|no route to host|host is unreachable",
    "tooManyConnections": r"too many (?:connections|clients)|remaining connection slots are reserved",
    "authenticationFailed": r"password authentication failed|authentication failed|no pg_hba\.conf entry",
}


class DiagnosticError(Exception):
    pass


def require(condition):
    if not condition:
        raise DiagnosticError("Invalid diagnostic configuration or response; values withheld.")


def ssh_arguments(env):
    from guard_diagnostic_commit import guard
    try:
        guard(env)
    except ValueError as exc:
        raise DiagnosticError('Diagnostic scope is not authorized; values withheld.') from None
    require(env.get("MM_READ_ONLY_DIAGNOSTICS") == "1"
            and env.get("CI_COMMIT_BRANCH") == env.get("CI_DEFAULT_BRANCH") == "main"
            and env.get("CI_COMMIT_REF_PROTECTED") == "true"
            and (env.get("CI_PIPELINE_SOURCE") in {"web", "api"}
                 or env.get("CI_PIPELINE_SOURCE")=="push" and env.get("MM_DIAGNOSTIC_COMMIT")=="1")
            and env.get("SERVICE_NAME") == CONTAINER
            and env.get("CI_DEBUG_TRACE", "false").lower() == "false")
    host, user, port = (env.get(name, "") for name in ("SERVER_HOST_TENZOR", "SSH_USER", "SSH_PORT_TENZOR"))
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}", host)
            and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{0,63}", user)
            and port.isdigit() and 1 <= int(port) <= 65535)
    key = Path(env.get("SSH_PRIVATE_KEY", ""))
    info = key.lstat()
    require(stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077)
    return ["ssh", "-i", str(key), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null", "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=1", "-p", port, user + "@" + host]


def container_state(output):
    raw = json.loads(output)
    require(raw.get("status") in {"created", "running", "paused", "restarting", "removing", "exited", "dead"})
    require(all(type(raw.get(field)) is bool for field in ("running", "restarting", "oomKilled")))
    require(type(raw.get("restartCount")) is int and raw["restartCount"] >= 0)
    require(all(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z", raw.get(field, ""))
                for field in ("startedAt", "finishedAt")))
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", raw.get("imageId", "")))
    tag = str(raw.get("image", "")).rsplit(":", 1)[-1]
    revision = raw.get("revision")
    return {key: raw[key] for key in ("status", "running", "restarting", "oomKilled", "restartCount", "startedAt", "finishedAt", "imageId")} | {
        "imageRevisionTag": tag if re.fullmatch(r"[a-f0-9]{40}", tag) else "UNEXPECTED",
        "imageRevisionLabel": revision if isinstance(revision, str) and re.fullmatch(r"[a-f0-9]{40}", revision) else "UNSET",
    }


def container_stats(output):
    raw = json.loads(output)
    require(all(re.fullmatch(r"[0-9]+(?:\.[0-9]+)?%", raw.get(field, ""))
                for field in ("cpuPercent", "memoryPercent")))
    require(re.fullmatch(r"[0-9.]+[ ]*[A-Za-z]+ / [0-9.]+[ ]*[A-Za-z]+", raw.get("memory", "")))
    require(str(raw.get("pids", "")).isdigit())
    return {"cpuPercent": raw["cpuPercent"], "memory": raw["memory"],
            "memoryPercent": raw["memoryPercent"], "pids": int(raw["pids"])}


def disk(output):
    result = []
    for line in output.splitlines()[1:]:
        fields = line.split()
        require(len(fields) == 6 and all(value.isdigit() for value in fields[1:4])
                and re.fullmatch(r"[0-9]+%", fields[4]) and fields[5] in {"/", "/var/lib/docker"})
        result.append({"mount": fields[5], "sizeKiB": int(fields[1]), "usedKiB": int(fields[2]),
                       "availableKiB": int(fields[3]), "usedPercent": int(fields[4][:-1])})
    require(bool(result))
    return result


def load(output):
    fields = output.split()
    require(len(fields) == 5 and all(re.fullmatch(r"[0-9]+\.[0-9]+", value) for value in fields[:3])
            and re.fullmatch(r"[0-9]+/[0-9]+", fields[3]))
    return {"load1": float(fields[0]), "load5": float(fields[1]), "load15": float(fields[2]),
            "runnableTasks": int(fields[3].split("/")[0]), "totalTasks": int(fields[3].split("/")[1])}


def memory(output):
    result = {}
    for name in ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree"):
        match = re.search(r"^" + name + r":[ ]+([0-9]+) kB$", output, re.MULTILINE)
        require(match is not None)
        result[name + "KiB"] = int(match.group(1))
    return result


def exception_counts(output):
    matches = re.findall(r"\b([A-Z][A-Za-z0-9$]*(?:Exception|Error))\b", output)
    lowered = output.lower()
    pool_states = []
    for line in output.splitlines():
        if "hikari" not in line.lower():
            continue
        match = re.search(r"\btotal=([0-9]{1,6}),\s*active=([0-9]{1,6}),\s*idle=([0-9]{1,6}),\s*waiting=([0-9]{1,6})\b", line)
        if match:
            values = [int(value) for value in match.groups()]
            pool_states.append(dict(zip(("total", "active", "idle", "waiting"), values)))
    migration_started = bool(re.search(r'Migrating schema .+ to version ["\x27]9\b', output))
    migration_finished = bool(re.search(r'Successfully applied .+ now at version v?9\b', output))
    application_started = bool(re.search(r'\bStarted \w+Application in [0-9.]+ seconds', output))
    return {"allowlistedExceptionMentions": {name: matches.count(name) for name in sorted(EXCEPTIONS) if name in matches},
            "otherExceptionMentions": sum(name not in EXCEPTIONS for name in matches),
            "hikariConnectionUnavailableMentions": sum("hikari" in line and "connection is not available" in line
                                                       for line in lowered.splitlines()),
            "failurePhraseMentions": {name: len(re.findall(pattern, lowered)) for name, pattern in FAILURE_PHRASES.items()},
            "latestHikariPoolState": pool_states[-1] if pool_states else None,
            "projectionMigrationStarted": migration_started,
            "projectionMigrationFinished": migration_finished,
            "applicationStarted": application_started,
            "sqlStates": sorted(set(re.findall(r'\bSQL State\s*:\s*([0-9A-Z]{5})\b', output))),
            "logWindowMinutes": 60, "maximumLogLines": 2000, "maximumLogBytes": 1048576,
            "logTruncatedAtByteLimit": len(output.encode()) >= 1048576}


def database_target(output):
    """Resolve only host/port privately; never return environment or credentials."""
    raw = json.loads(output)
    require(str(raw.get("image", "")).rsplit(":", 1)[-1] == REVIEWED_DATABASE_IMAGE
            and raw.get("expectedCommand") is True and raw.get("unsupportedOverrides") is False
            and raw.get("workingDir") == "/app")
    entries = raw.get("env")
    require(isinstance(entries, list) and len(entries) <= 1000)
    env = {}
    for entry in entries:
        require(isinstance(entry, str) and "=" in entry and "\n" not in entry and "\r" not in entry)
        key, value = entry.split("=", 1)
        require(key not in env)
        env[key] = value
    require(set(env).issubset(TARGET_ENV_KEYS)
            and env.get("SPRING_PROFILES_ACTIVE", "prod").strip() == "prod"
            and env.get("SPRING_PROFILES_DEFAULT", "prod").strip() == "prod"
            and env.get("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.postgresql.Driver") == "org.postgresql.Driver")
    mounts = raw.get("mounts")
    require(isinstance(mounts, list))
    for mount in mounts:
        destination = mount.get("Destination", "").rstrip("/")
        require(destination not in {"/app", "/app/config", "/config"}
                and not destination.startswith(("/app/application", "/config/", "/app/config/")))
    source = "SPRING_DATASOURCE_URL" if "SPRING_DATASOURCE_URL" in env else "DB_URL" if "DB_URL" in env else "REVIEWED_SHIPPED_DEFAULT"
    if source == "REVIEWED_SHIPPED_DEFAULT":
        return (*REVIEWED_DATABASE_TARGET, source)
    value = env[source]
    require(value.startswith("jdbc:postgresql://") and not any(character in value for character in "\r\n\x00"))
    parsed = urlsplit(value[5:])
    host, port = parsed.hostname, 5432 if parsed.port is None else parsed.port
    require(host is not None and parsed.username is None and parsed.password is None
            and 1 <= port <= 65535 and bool(parsed.path) and "," not in parsed.netloc
            and not parsed.query and not parsed.fragment)
    if ":" in host:
        ipaddress.IPv6Address(host)
    else:
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}", host))
    return host, port, source


def tcp_result(arguments, command, runner, origin):
    record = {"probeOrigin": origin}
    try:
        started = time.monotonic()
        completed = runner(arguments + [command], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=12, check=False)
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        record["exitCode"] = completed.returncode
        stderr = completed.stderr.lower()
        if completed.returncode == 0:
            outcome = "CONNECTED"
        elif completed.returncode in {124, 137} or "timed out" in stderr:
            outcome = "TIMED_OUT"
        elif "connection refused" in stderr:
            outcome = "REFUSED"
        elif "network is unreachable" in stderr or "no route to host" in stderr:
            outcome = "UNREACHABLE"
        elif "name or service not known" in stderr or "temporary failure in name resolution" in stderr:
            outcome = "DNS_FAILURE"
        else:
            outcome = "FAILED"
        record["connectionOutcome"] = outcome
    except subprocess.TimeoutExpired:
        record["transportError"] = "TIMEOUT"
    except OSError:
        record["transportError"] = "SSH_EXECUTION_FAILED"
    return record


def database_tcp_probe(arguments, runner):
    record = {"checksPostgresqlHealth": False, "targetPrinted": False}
    try:
        inspected = runner(arguments + ["docker inspect --format " + shlex.quote(DATABASE_INSPECT_FORMAT) + " " + CONTAINER],
                           capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=12, check=False)
        require(inspected.returncode == 0)
        host, port, source = database_target(inspected.stdout)
        record["targetSource"] = source
        # Open one socket per route; send no PostgreSQL handshake or credentials.
        command = "timeout --signal=TERM --kill-after=1s 5s bash -c " + shlex.quote('exec 3<>/dev/tcp/"$1"/"$2"') + " -- " + shlex.quote(host) + " " + str(port)
        record["serverHost"] = tcp_result(arguments, command, runner, "SERVER_HOST_NETWORK")
        record["backendContainer"] = tcp_result(arguments, "docker exec " + CONTAINER + " " + command, runner, "BACKEND_CONTAINER_NETWORK")
    except (DiagnosticError, ValueError, TypeError, KeyError, AttributeError):
        record["responseValidated"] = False
    except subprocess.TimeoutExpired:
        record["transportError"] = "TIMEOUT"
    except OSError:
        record["transportError"] = "SSH_EXECUTION_FAILED"
    return record


def health_command(url):
    require(url in HEALTH_URLS.values())
    timing = TIMING_MARKER + '{"httpStatus":"%{http_code}","connectSeconds":%{time_connect},"tlsSeconds":%{time_appconnect},"firstByteSeconds":%{time_starttransfer},"totalSeconds":%{time_total}}'
    return "curl --silent --show-error --connect-timeout 3 --max-time 8 --max-filesize 16384 --write-out '" + timing + "' '" + url + "'"


def health(output):
    body, timing = output.rsplit(TIMING_MARKER, 1)
    raw = json.loads(timing)
    require(re.fullmatch(r"[0-9]{3}", raw.get("httpStatus", "")))
    require(all(type(raw.get(field)) in (float, int) and 0 <= raw[field] <= 30
                for field in ("connectSeconds", "tlsSeconds", "firstByteSeconds", "totalSeconds")))
    status = "NO_VALID_HEALTH_BODY"
    try:
        candidate = json.loads(body).get("status")
        if candidate in {"UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"}:
            status = candidate
    except (ValueError, TypeError, AttributeError):
        pass
    return raw | {"healthStatus": status}


def projection_activity_probe(arguments, runner):
    from projection_activity_diagnostics import safe_report
    root = Path(__file__).resolve().parents[1]
    source = (root/'scripts/projection_activity_diagnostics.py').read_text()
    hashes = [hashlib.sha256(path.read_bytes()).hexdigest() for path in
              (root/'scripts/predeploy_backup.py',root/'src/main/resources/application-prod.yml')]
    command = 'python3 -c ' + shlex.quote(source) + ' ' + ' '.join(hashes)
    try:
        result = runner(arguments+[command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=50,check=False)
        require(result.returncode==0 and len(result.stdout.encode())<16384)
        return {'data':safe_report(json.loads(result.stdout)),'databaseMutations':0,'usesEphemeralReadOnlyClient':True}
    except subprocess.TimeoutExpired:
        return {'transportError':'TIMEOUT'}
    except (DiagnosticError,OSError,ValueError,TypeError,KeyError,AttributeError):
        return {'responseValidated':False}


def native_postgres_probe(arguments, runner):
    from run_native_postgres_recovery import remote_source, safe_report
    command = 'python3 -c ' + shlex.quote(remote_source(recover=False))
    try:
        result = runner(arguments + [command], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=50, check=False)
        require(result.returncode == 0 and len(result.stdout.encode()) < 4096)
        return {'data': safe_report(json.loads(result.stdout)), 'persistentRemoteMutations': 0}
    except subprocess.TimeoutExpired:
        return {'transportError': 'TIMEOUT'}
    except Exception:
        return {'responseValidated': False}


def collect(arguments, runner=subprocess.run):
    probes = {
        "containerState": ("docker inspect --format '" + INSPECT_FORMAT + "' " + CONTAINER, container_state),
        "containerStats": ("docker stats --no-stream --format '" + STATS_FORMAT + "' " + CONTAINER, container_stats),
        "hostDisk": ("df -Pk / /var/lib/docker", disk),
        "hostLoad": ("cat /proc/loadavg", load),
        "hostMemory": ("cat /proc/meminfo", memory),
        "databaseHostLocation": ("hostname -I", database_host_location),
        "databaseDockerLocation": (DOCKER_NETWORK_COMMAND, database_docker_location),
        "postgresClusters": ("pg_lsclusters", postgres_clusters),
        "postgresUnits": (POSTGRES_UNITS_COMMAND, postgres_units),
        "postgresUnitFiles": (POSTGRES_UNIT_FILES_COMMAND, postgres_unit_files),
        "postgresContainers": (POSTGRES_CONTAINER_COMMAND, postgres_containers),
        "recentExceptionCounts": ("bash -o pipefail -c " + shlex.quote("docker logs --since 60m --tail 2000 " + CONTAINER + " 2>&1 | head -c 1048576"), exception_counts),
    } | {name: (health_command(url), health) for name, url in HEALTH_URLS.items()}
    report = {"format": "mm-read-only-diagnostics-v2", "collectedAt": datetime.now(timezone.utc).isoformat(),
              "persistentRemoteMutations": 0, "databaseMutations": 0, "rawLogTextPrinted": False, "probes": {}}
    for name, (command, parser) in probes.items():
        record = {}
        started = time.monotonic()
        try:
            completed = runner(arguments + [command], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15, check=False)
            record["exitCode"] = completed.returncode
            if completed.returncode == 255:
                record["transportError"] = ssh_failure_category(completed.stderr)
            # stderr and raw stdout never enter the report, including SSH/HTTP failures.
            try:
                require(completed.returncode == 0 or name in HEALTH_URLS and completed.returncode == 28
                        or name == "hostDisk" and completed.returncode == 1
                        or name == "recentExceptionCounts" and completed.returncode == 141
                           and len(completed.stdout.encode()) >= 1048576)
                record["data"] = parser(completed.stdout)
            except (DiagnosticError, ValueError, TypeError, KeyError, AttributeError):
                record["responseValidated"] = False
        except subprocess.TimeoutExpired:
            record["transportError"] = "TIMEOUT"
        except OSError:
            record["transportError"] = "SSH_EXECUTION_FAILED"
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        report["probes"][name] = record
    report["probes"]["databaseTcp"] = database_tcp_probe(arguments, runner)
    report["probes"]["projectionActivity"] = projection_activity_probe(arguments,runner)
    report["probes"]["nativePostgresPreflight"] = native_postgres_probe(arguments, runner)
    return report


def database_host_location(output):
    values = output.split()
    require(0 < len(values) <= 128)
    addresses = [ipaddress.ip_address(value) for value in values]
    return {"reviewedDatabaseHostIsLocal": ipaddress.ip_address(REVIEWED_DATABASE_TARGET[0]) in addresses,
            "hostAddressesPrinted": False}


def database_docker_location(output):
    require(len(output) <= 2 * 1024 * 1024)
    target = ipaddress.ip_address(REVIEWED_DATABASE_TARGET[0])
    matches = False
    for line in output.splitlines():
        networks = json.loads(line)
        require(isinstance(networks, dict))
        for network in networks.values():
            require(isinstance(network, dict))
            configured = network.get("IPAMConfig") or {}
            require(isinstance(configured, dict))
            for value in (network.get("IPAddress"), network.get("GlobalIPv6Address"),
                          configured.get("IPv4Address"), configured.get("IPv6Address")):
                if value:
                    matches |= ipaddress.ip_address(value) == target
    return {"reviewedDatabaseTargetMatchesDockerNetwork": matches, "containerAddressesPrinted": False}


def postgres_clusters(output):
    require(len(output) <= 65536)
    clusters = []
    for line in output.splitlines():
        if not line.strip() or line.startswith("Ver "):
            continue
        fields = line.split(None, 6)
        require(len(fields) == 7 and re.fullmatch(r"\d{1,2}(?:\.\d)?", fields[0])
                and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", fields[1])
                and fields[2].isdigit() and re.fullmatch(r"(?:online|down)(?:,[a-z_]+)*", fields[3]))
        if int(fields[2]) == REVIEWED_DATABASE_TARGET[1]:
            clusters.append({"version": fields[0], "cluster": fields[1], "status": fields[3], "configuredPortMatches": True})
    return {"clusters": clusters, "ownersAndPathsPrinted": False}


def systemd_inventory_lines(output):
    require(len(output) <= 65536)
    lines = []
    for line in output.splitlines():
        line = re.sub(r"\x1b\[[0-9;]*m", "", line).strip().lstrip("● ")
        if not line or re.fullmatch(r"\d+ (?:loaded units|unit files) listed\.", line) or line in {
            "To show all installed unit files use 'systemctl list-unit-files'.", "UNIT LOAD ACTIVE SUB DESCRIPTION", "UNIT FILE STATE PRESET"}:
            continue
        lines.append(line)
    return lines


def systemd_inventory_result(key, rows, unrecognized):
    return {key: rows, "descriptionsPrinted": False, "unrecognizedLineCount": unrecognized,
            "parserErrorCategory": "UNRECOGNIZED_SYSTEMD_LINES" if unrecognized else "NONE"}


def postgres_units(output):
    units, unrecognized = [], 0
    for line in systemd_inventory_lines(output):
        fields = line.split(None, 4)
        if not (len(fields) >= 4 and re.fullmatch(POSTGRES_UNIT_PATTERN, fields[0])
                and fields[1] in {"loaded", "not-found", "bad-setting", "error", "masked"}
                and fields[2] in {"active", "reloading", "inactive", "failed", "activating", "deactivating", "maintenance", "refreshing"}
                and fields[3] in {"running", "exited", "dead", "failed", "start-pre", "start", "start-post", "auto-restart",
                                  "stop", "stop-post", "final-sigterm", "final-sigkill", "cleaning", "reload", "condition", "waiting"}):
            unrecognized += 1
            continue
        units.append(dict(zip(("unit", "loadState", "activeState", "subState"), fields[:4])))
    return systemd_inventory_result("units", units, unrecognized)


def postgres_unit_files(output):
    files, unrecognized = [], 0
    states = {"enabled", "enabled-runtime", "disabled", "static", "indirect", "masked", "masked-runtime",
              "generated", "transient", "alias", "bad", "linked", "linked-runtime"}
    for line in systemd_inventory_lines(output):
        fields = line.split()
        if not (2 <= len(fields) <= 3 and re.fullmatch(POSTGRES_UNIT_PATTERN, fields[0]) and fields[1] in states
                and (len(fields) == 2 or fields[2] in {"enabled", "disabled", "ignored"})):
            unrecognized += 1
            continue
        row = {"unit": fields[0], "unitFileState": fields[1]}
        if len(fields) == 3:
            row["preset"] = fields[2]
        files.append(row)
    return systemd_inventory_result("unitFiles", files, unrecognized)


def postgres_containers(output):
    require(len(output) <= 2 * 1024 * 1024)
    containers = []
    for line in output.splitlines():
        value = json.loads(line)
        require(isinstance(value, dict))
        bindings = value.get("portBindings") or {}
        require(isinstance(bindings, dict))
        relevant = any(str(binding.get("HostPort")) == str(REVIEWED_DATABASE_TARGET[1])
                       and binding.get("HostIp", "") in ("", "0.0.0.0", "::", REVIEWED_DATABASE_TARGET[0])
                       for values in bindings.values() for binding in (values or []))
        image = value.get("image", "")
        kind = "POSTGRESQL" if value.get("postgresCommand") is True or re.search(
            r"(?:^|/)(?:postgres|postgresql|pgvector|timescaledb)(?::|@|$)", image) else (
            "PGBOUNCER" if re.search(r"(?:^|/)pgbouncer(?::|@|$)", image) else "UNRECOGNIZED")
        host_network = value.get("usesHostNetwork") is True
        if not relevant and not (host_network and kind in {"POSTGRESQL", "PGBOUNCER"}):
            continue
        name = value.get("name", "").lstrip("/")
        require(re.fullmatch(r"[a-f0-9]{64}", value.get("id", ""))
                and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", name)
                and value.get("status") in {"created", "running", "paused", "restarting", "removing", "exited", "dead"}
                and isinstance(value.get("oomKilled"), bool)
                and all(isinstance(value.get(key), int) and not isinstance(value[key], bool) for key in ("exitCode", "restartCount"))
                and value.get("restartPolicy") in {"no", "always", "unless-stopped", "on-failure"})
        containers.append({"id": value["id"], "name": name, "status": value["status"], "oomKilled": value["oomKilled"],
                           "exitCode": value["exitCode"], "restartCount": value["restartCount"],
                           "restartPolicy": value["restartPolicy"], "configuredPortMatches": relevant, "serviceKind": kind,
                           "usesHostNetwork": host_network, "requiresPortVerification": not relevant})
    return {"containers": containers, "addressesImagesAndUnrelatedContainersPrinted": False}


def ssh_failure_category(stderr):
    # Return only fixed categories; host names, users, key paths and raw stderr stay private.
    value = (stderr or "")[:16384].lower()
    for category, pattern in (
        ("TIMEOUT", r"timed out|connection timeout"),
        ("CONNECTION_REFUSED", r"connection refused"),
        ("NETWORK_UNREACHABLE", r"network is unreachable|no route to host|host is unreachable"),
        ("DNS_FAILED", r"could not resolve hostname|name or service not known|temporary failure in name resolution"),
        ("AUTHENTICATION_FAILED", r"permission denied|authentication failed|too many authentication failures"),
        ("KEY_UNAVAILABLE", r"load key|identity file.*not accessible|unprotected private key file"),
        ("HOST_KEY_FAILED", r"host key verification failed|remote host identification has changed"),
        ("SSH_HANDSHAKE_FAILED", r"kex_exchange_identification|connection closed|connection reset"),
    ):
        if re.search(pattern, value):
            return category
    return "UNCLASSIFIED_SSH_FAILURE"


def main():
    print(json.dumps(collect(ssh_arguments(os.environ)), sort_keys=True), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (DiagnosticError, OSError, ValueError, TypeError) as exc:
        print("Read-only diagnostics stopped: " + type(exc).__name__ + "; configuration values withheld.", file=sys.stderr)
        sys.exit(1)
