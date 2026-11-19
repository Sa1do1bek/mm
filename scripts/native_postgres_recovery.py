"""Inspect, or explicitly start once, the existing failed MM PostgreSQL unit.

Run on the deployment host. Paths, environments and configuration stay there.
No initdb, restore, reset, deletion, configuration change or stock SQL is used.
"""
import errno
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import shutil
import socket
import stat
import subprocess
import time
from urllib import request

import read_only_diagnostics as diagnostics

UNIT = 'postgresql-17.service'
BACKEND_IMAGE = 'sha256:05de9f1f520042160a5456500340d95c131a7c48a9c397c3e389a442063dc3b3'
BACKEND_STARTED_AT = '2026-10-11T19:36:30.185731933Z'
RECEIPT = 'postgresql-17-start-2026-10-12.json'
PROPERTIES = ('Id', 'LoadState', 'ActiveState', 'SubState', 'Result', 'ExecMainCode', 'ExecMainStatus',
              'MainPID', 'User', 'Group', 'Environment', 'EnvironmentFiles', 'DropInPaths', 'FragmentPath',
              'ExecStart', 'ExecStartPre', 'ExecStartPost', 'ExecStop', 'ExecStopPost', 'ExecCondition', 'InvocationID')
UNIT_BUS_PATH = '/org/freedesktop/systemd1/unit/postgresql_2d17_2eservice'
EMPTY_ARRAY_SIGNATURES = {'EnvironmentFiles': 'a(sb)', 'ExecCondition': 'a(sasbttttuii)',
                          'ExecStartPost': 'a(sasbttttuii)', 'ExecStop': 'a(sasbttttuii)', 'ExecStopPost': 'a(sasbttttuii)'}
FAILURES = {'oom-kill', 'signal', 'core-dump', 'exit-code', 'timeout', 'resources', 'start-limit-hit', 'protocol'}
STAGES = {'PREFLIGHT', 'UNIT', 'BACKEND', 'TARGET_LISTENER', 'CLUSTER', 'CONFIGURATION', 'RESOURCES',
          'READY', 'ATTEMPT_RECEIPT', 'IDENTITY_RECHECK', 'START_EXISTING_UNIT', 'WAIT_READY', 'POST_START_VERIFY'}
STAGE = 'PREFLIGHT'


class RecoveryError(Exception):
    pass


def require(value):
    if not value:
        raise RecoveryError('Native recovery guard failed; private values withheld.')


def command(arguments, timeout=15):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout, check=False,
                            env=os.environ | {'LC_ALL': 'C', 'SYSTEMD_COLORS': '0', 'SYSTEMD_URLIFY': '0'})
    require(result.returncode == 0 and len(result.stdout.encode()) <= 2 * 1024 * 1024)
    return result.stdout


def unit_properties(output):
    values, summary = parse_unit_snapshot(output)
    require(summary['formatCategory'] in {'VALID', 'MISSING_PROPERTIES'})
    if summary['missingProperties']:
        values = normalize_empty_unit_arrays(values)
    require(set(values) == set(PROPERTIES))
    return values


def typed_array_command(property_name):
    require(property_name in EMPTY_ARRAY_SIGNATURES)
    return ['busctl', '--system', '--no-pager', '--timeout=5', 'get-property', 'org.freedesktop.systemd1',
            UNIT_BUS_PATH, 'org.freedesktop.systemd1.Service', property_name]


def normalize_empty_unit_arrays(values, reader=None):
    missing = set(PROPERTIES) - set(values)
    require(missing <= set(EMPTY_ARRAY_SIGNATURES) and values.get('Id') == UNIT and values.get('LoadState') == 'loaded')
    reader = command if reader is None else reader
    normalized = dict(values)
    for property_name in sorted(missing):
        output = reader(typed_array_command(property_name), timeout=7)
        require(output.strip() == EMPTY_ARRAY_SIGNATURES[property_name] + ' 0')
        normalized[property_name] = ''
    return normalized


def unit_snapshot_command():
    return ['systemctl', 'show', '--all', '--no-pager', '--property=' + ','.join(PROPERTIES), UNIT]


def unit_snapshot():
    return unit_properties(command(unit_snapshot_command()))


def parse_unit_snapshot(output):
    values, unknown, duplicates = {}, 0, 0
    oversized = len(output) > 65536
    if not oversized:
        for line in output.splitlines():
            key, separator, value = line.partition('=')
            if not separator or key not in PROPERTIES:
                unknown += 1
            elif key in values:
                duplicates += 1
            else:
                values[key] = value
    missing = sorted(set(PROPERTIES) - set(values))
    category = ('OVERSIZED_OUTPUT' if oversized else 'UNRECOGNIZED_PROPERTY_LINES' if unknown else
                'DUPLICATE_PROPERTIES' if duplicates else 'MISSING_PROPERTIES' if missing else 'VALID')
    return values, {'propertiesPresent': sorted(values), 'missingProperties': missing, 'unknownLineCount': unknown,
                    'duplicatePropertyCount': duplicates, 'formatCategory': category}


def empty_snapshot_diagnostics():
    return parse_unit_snapshot('')[1] | {'commandCategory': 'NOT_QUERIED', 'snapshotExitCode': None,
        'systemdVersion': None, 'versionReadCategory': 'NOT_QUERIED', 'typedEmptyArrayProof': [],
        'missingArrayPropertiesVerifiedEmpty': False}


def systemd_version(output):
    require(len(output) <= 65536)
    match = re.match(r'^systemd (\d{1,4})(?:\s|$)', output)
    require(match is not None and 100 <= int(match[1]) <= 9999)
    return int(match[1])


def snapshot_diagnostics():
    summary, values = empty_snapshot_diagnostics(), {}
    environment = dict(os.environ, LC_ALL='C', SYSTEMD_COLORS='0', SYSTEMD_URLIFY='0')
    try:
        result = subprocess.run(unit_snapshot_command(), capture_output=True, text=True, timeout=15, check=False, env=environment)
        values, presence = parse_unit_snapshot(result.stdout)
        summary.update(presence)
        summary['commandCategory'] = 'SUCCESS' if result.returncode == 0 else 'NONZERO_EXIT'
        summary['snapshotExitCode'] = result.returncode if -255 <= result.returncode <= 255 else None
    except subprocess.TimeoutExpired:
        summary['commandCategory'] = 'TIMEOUT'
    except OSError:
        summary['commandCategory'] = 'EXECUTION_UNAVAILABLE'
    if values.get('Id') == UNIT and values.get('LoadState') == 'loaded':
        for property_name in sorted(set(summary['missingProperties']) & set(EMPTY_ARRAY_SIGNATURES)):
            proof = {'property': property_name, 'expectedSignature': EMPTY_ARRAY_SIGNATURES[property_name],
                     'verifiedEmpty': False, 'readCategory': 'READ_UNVERIFIED'}
            try:
                output = command(typed_array_command(property_name), timeout=7)
                proof['verifiedEmpty'] = output.strip() == EMPTY_ARRAY_SIGNATURES[property_name] + ' 0'
                proof['readCategory'] = 'EMPTY_VERIFIED' if proof['verifiedEmpty'] else 'NONEMPTY_OR_UNRECOGNIZED'
            except Exception:
                pass
            summary['typedEmptyArrayProof'].append(proof)
    summary['missingArrayPropertiesVerifiedEmpty'] = (
        summary['commandCategory'] == 'SUCCESS' and set(summary['missingProperties']) <= set(EMPTY_ARRAY_SIGNATURES)
        and len(summary['typedEmptyArrayProof']) == len(summary['missingProperties'])
        and all(proof['verifiedEmpty'] for proof in summary['typedEmptyArrayProof']))
    try:
        result = subprocess.run(['systemctl', '--version'], capture_output=True, text=True, timeout=5, check=False, env=environment)
        summary['versionReadCategory'] = 'SUCCESS' if result.returncode == 0 else 'NONZERO_EXIT'
        if result.returncode == 0:
            try:
                summary['systemdVersion'] = systemd_version(result.stdout)
            except RecoveryError:
                summary['versionReadCategory'] = 'UNRECOGNIZED_VERSION'
    except subprocess.TimeoutExpired:
        summary['versionReadCategory'] = 'TIMEOUT'
    except OSError:
        summary['versionReadCategory'] = 'EXECUTION_UNAVAILABLE'
    return summary, failure_report(values)


def failure_report(values):
    def number(key):
        value = values.get(key, '')
        return int(value) if re.fullmatch(r'\d{1,3}', value) and int(value) <= 255 else None
    return {'failureResult': values.get('Result') if values.get('Result') in FAILURES else 'UNAVAILABLE',
            'execMainCode': number('ExecMainCode'), 'execMainStatus': number('ExecMainStatus')}


def unit_argv(value):
    matches = re.findall(r'path=([^ ;]+) ; argv\[\]=([^;]+) ;', value)
    require(len(matches) == 1 and value.count('argv[]=') == 1 and '; ignore_errors=no ;' in value)
    path, raw = matches[0]
    arguments = shlex.split(raw)
    require(arguments and path == arguments[0])
    return arguments


def validate_unit(values, failed=True):
    require(values['Id'] == UNIT and values['LoadState'] == 'loaded' and values['User'] == 'postgres'
            and values['Group'] in {'', 'postgres'} and not values['EnvironmentFiles'] and not values['DropInPaths']
            and not values['ExecStartPost'] and not values['ExecStop'] and not values['ExecStopPost'] and not values['ExecCondition'])
    if failed:
        require(values['ActiveState'] == values['SubState'] == 'failed' and values['MainPID'] == '0'
                and values['Result'] in FAILURES)
    else:
        require(values['ActiveState'] == 'active' and values['SubState'] == 'running' and int(values['MainPID']) > 1)
    environment = {}
    for entry in shlex.split(values['Environment']):
        key, separator, value = entry.partition('=')
        require(separator and key not in environment and key in {'PGDATA', 'PG_OOM_ADJUST_FILE', 'PG_OOM_ADJUST_VALUE'})
        environment[key] = value
    oom_keys = {'PG_OOM_ADJUST_FILE', 'PG_OOM_ADJUST_VALUE'} & set(environment)
    require(not oom_keys or (oom_keys == {'PG_OOM_ADJUST_FILE', 'PG_OOM_ADJUST_VALUE'}
            and environment['PG_OOM_ADJUST_FILE'] == '/proc/self/oom_score_adj' and environment['PG_OOM_ADJUST_VALUE'] == '0'))
    data = Path(environment.get('PGDATA', ''))
    require(data.is_absolute() and not any(c in str(data) for c in '\r\n\x00'))
    server, check = unit_argv(values['ExecStart']), unit_argv(values['ExecStartPre'])
    require(server[0] in {'/usr/pgsql-17/bin/postgres', '/usr/pgsql-17/bin/postmaster'}
            and server[1:] == ['-D', '${PGDATA}']
            and check == ['/usr/pgsql-17/bin/postgresql-17-check-db-dir', '${PGDATA}'])
    require(values['FragmentPath'] == '/usr/lib/systemd/system/' + UNIT)
    return {'data': data, 'server': [server[0], '-D', str(data)], 'check': check[0], 'fragment': values['FragmentPath']}


def validate_cluster(data, uid):
    info = data.stat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == uid and not info.st_mode & 0o027)
    version, control = data / 'PG_VERSION', data / 'global/pg_control'
    require(version.is_file() and version.stat().st_size <= 16 and version.read_text().strip() == '17'
            and control.is_file() and 0 < control.stat().st_size <= 65536
            and all((data / name).is_dir() for name in ('base', 'global', 'pg_wal')))
    pid_file = data / 'postmaster.pid'
    if pid_file.exists():
        require(pid_file.stat().st_size < 4096)
        pid = pid_file.read_text().splitlines()[0]
        require(pid.isdigit() and int(pid) > 1)
        try:
            os.kill(int(pid), 0)
        except ProcessLookupError:
            pass
        else:
            raise RecoveryError('An existing process prevents a safe service start.')


def validate_effective_config(values, data):
    require(values['port'] == str(diagnostics.REVIEWED_DATABASE_TARGET[1])
            and any(address.strip() in {'*', '0.0.0.0', diagnostics.REVIEWED_DATABASE_TARGET[0]}
                    for address in values['listen_addresses'].split(','))
            and Path(values['data_directory']).resolve() == data.resolve())


def configuration_fingerprint(values, context):
    paths = [Path(context['fragment']), Path(context['server'][0]), Path(context['check']), context['data'] / 'PG_VERSION',
             context['data'] / 'postgresql.conf', context['data'] / 'postgresql.auto.conf']
    require(all(path.is_file() for path in paths))
    hashes = [(str(path), hashlib.sha256(path.read_bytes()).hexdigest()) for path in paths]
    material = {'files': hashes, 'environment': values['Environment'], 'server': context['server'], 'check': context['check']}
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode()).hexdigest()


def backend_state():
    state = diagnostics.container_state(command(['docker', 'inspect', '--format', diagnostics.INSPECT_FORMAT, diagnostics.CONTAINER]))
    require(state['running'] is True and state['imageId'] == BACKEND_IMAGE
            and state['imageRevisionTag'] == diagnostics.REVIEWED_DATABASE_IMAGE
            and state['startedAt'] == BACKEND_STARTED_AT and state['restartCount'] == 0)
    return state


def require_refused():
    try:
        with socket.create_connection(diagnostics.REVIEWED_DATABASE_TARGET, timeout=2):
            raise RecoveryError('An existing listener prevents a safe service start.')
    except OSError as error:
        require(error.errno == errno.ECONNREFUSED)


def preflight():
    global STAGE
    STAGE = 'UNIT'
    values = unit_snapshot()
    context = validate_unit(values)
    units = diagnostics.postgres_units(command(shlex.split(diagnostics.POSTGRES_UNITS_COMMAND)))
    require(units['unrecognizedLineCount'] == 0 and [row['unit'] for row in units['units']] == [UNIT])
    require(re.fullmatch(r'[a-f0-9]{32}', values['InvocationID']))
    STAGE = 'BACKEND'
    backend_state()
    host, port, _ = diagnostics.database_target(command(['docker', 'inspect', '--format', diagnostics.DATABASE_INSPECT_FORMAT, diagnostics.CONTAINER]))
    require((host, port) == diagnostics.REVIEWED_DATABASE_TARGET)
    require(diagnostics.database_host_location(command(['hostname', '-I']))['reviewedDatabaseHostIsLocal'])
    require(not diagnostics.postgres_containers(command(shlex.split(diagnostics.POSTGRES_CONTAINER_COMMAND)))['containers'])
    STAGE = 'TARGET_LISTENER'
    require_refused()
    STAGE = 'CLUSTER'
    validate_cluster(context['data'], pwd.getpwnam('postgres').pw_uid)
    STAGE = 'CONFIGURATION'
    require(os.access(context['server'][0], os.X_OK) and os.access(context['check'], os.X_OK))
    require(Path(context['server'][0]).resolve() == Path('/usr/pgsql-17/bin/postgres').resolve())
    config = {key: command(['runuser', '-u', 'postgres', '--', 'env', '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C']
                           + context['server'] + ['-C', key]).strip() for key in ('port', 'listen_addresses', 'data_directory')}
    validate_effective_config(config, context['data'])
    context['configuration'] = configuration_fingerprint(values, context)
    STAGE = 'RESOURCES'
    memory = diagnostics.memory(Path('/proc/meminfo').read_text())
    require(memory['MemAvailableKiB'] >= 8 * 1024 * 1024 and shutil.disk_usage(context['data']).free >= 10 * 1024**3
            and os.getloadavg()[0] < 8)
    context['identity'] = hashlib.sha256(json.dumps({'unit': values, 'configuration': context['configuration'],
        'dataIdentity': (context['data'].stat().st_dev, context['data'].stat().st_ino),
        'control': hashlib.sha256((context['data'] / 'global/pg_control').read_bytes()).hexdigest()}, sort_keys=True).encode()).hexdigest()
    context['backendImage'] = BACKEND_IMAGE
    context['failure'] = failure_report(values)
    STAGE = 'READY'
    return context


def inspect():
    passed = False
    failure = {'failureResult': 'UNAVAILABLE', 'execMainCode': None, 'execMainStatus': None}
    try:
        context = preflight()
        failure = context['failure']
        passed = True
    except Exception:
        pass
    snapshot, partial_failure = snapshot_diagnostics()
    if not passed:
        failure = partial_failure
    return {'format': 'mm-native-postgres-preflight-v1', 'service': UNIT, 'guardsPassed': passed, 'stage': STAGE,
            **failure, 'unitSnapshot': snapshot, 'rawConfigurationPrinted': False, 'persistentRemoteMutations': 0}


def receipt_folder():
    folder = Path.home() / '.local/share/mm-native-postgres-recovery'
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not folder.is_symlink() and stat.S_ISDIR(folder.lstat().st_mode) and not folder.stat().st_mode & 0o077)
    return folder


def health(url):
    class NoRedirect(request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None
    try:
        opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
        with opener.open(url, timeout=3) as response:
            return response.status == 200 and json.load(response).get('status') == 'UP'
    except Exception:
        return False


def wait_ready(context):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            command(['/usr/pgsql-17/bin/pg_isready', '-h', diagnostics.REVIEWED_DATABASE_TARGET[0],
                     '-p', str(diagnostics.REVIEWED_DATABASE_TARGET[1]), '-t', '2'], timeout=4)
            if all(health(url) for url in diagnostics.HEALTH_URLS.values()):
                return
        except Exception:
            pass
        time.sleep(2)
    raise RecoveryError('Readiness was not verified; the existing start is not retried.')


def post_start_verify(context):
    values = unit_snapshot()
    current = validate_unit(values, failed=False)
    require(configuration_fingerprint(values, current) == context['configuration'])
    backend_state()


def recover():
    global STAGE
    before = preflight()
    STAGE = 'ATTEMPT_RECEIPT'
    destination = receipt_folder() / RECEIPT
    require(not destination.exists())
    STAGE = 'IDENTITY_RECHECK'
    current = preflight()
    STAGE = 'IDENTITY_RECHECK'
    require(current['identity'] == before['identity'])
    STAGE = 'ATTEMPT_RECEIPT'
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump({'unit': UNIT, 'identity': before['identity'], 'startAttemptRecorded': True}, stream)
        stream.flush()
        os.fsync(stream.fileno())
    STAGE = 'START_EXISTING_UNIT'
    command(['systemctl', 'start', UNIT], timeout=90)
    STAGE = 'WAIT_READY'
    wait_ready(before)
    STAGE = 'POST_START_VERIFY'
    post_start_verify(before)
    return {'format': 'mm-native-postgres-recovery-v1', 'service': UNIT, **before['failure'], 'serviceStartedOnce': True,
            'postgresReady': True, 'localMmReady': True, 'publicMmReady': True, 'backendImageUnchanged': True,
            'configurationUnchanged': True, 'databaseRestorePerformed': False, 'stockWritesPerformed': False}
