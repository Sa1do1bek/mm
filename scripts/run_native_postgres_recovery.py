"""Protected-main transport; inspection never selects the service-start path."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

import native_postgres_recovery as native
from read_only_diagnostics import ssh_arguments


def validate_recovery_mode(env):
    native.require(env.get('MM_RECOVER_NATIVE_POSTGRES') == '1' and env.get('CI_JOB_NAME') == 'recover_native_postgres')


def remote_source(recover=False):
    root = Path(__file__).resolve().parent
    parts = ['import sys,types,json\nsys.dont_write_bytecode=True']
    for name in ('read_only_diagnostics', 'native_postgres_recovery'):
        source = (root / (name + '.py')).read_text()
        parts.append('m=types.ModuleType(' + repr(name) + ');sys.modules[m.__name__]=m;exec(' + repr(source) + ',m.__dict__)')
    parts.append('native=sys.modules["native_postgres_recovery"];print(json.dumps(native.' + ('recover' if recover else 'inspect') + '(),sort_keys=True))')
    return '\n'.join(parts)


def safe_report(value, recovered=False):
    common = {'format', 'service', 'failureResult', 'execMainCode', 'execMainStatus'}
    extra = ({'serviceStartedOnce', 'postgresReady', 'localMmReady', 'publicMmReady', 'backendImageUnchanged',
              'configurationUnchanged', 'databaseRestorePerformed', 'stockWritesPerformed'} if recovered else
             {'guardsPassed', 'stage', 'rawConfigurationPrinted', 'persistentRemoteMutations', 'unitSnapshot'})
    native.require(isinstance(value, dict) and set(value) == common | extra and value['service'] == native.UNIT
                   and value['format'] == ('mm-native-postgres-recovery-v1' if recovered else 'mm-native-postgres-preflight-v1')
                   and value['failureResult'] in native.FAILURES | {'UNAVAILABLE'}
                   and all(value[key] is None or type(value[key]) is int and 0 <= value[key] <= 255 for key in ('execMainCode', 'execMainStatus')))
    if recovered:
        native.require(all(value[key] is True for key in extra - {'databaseRestorePerformed', 'stockWritesPerformed'})
                       and value['databaseRestorePerformed'] is False and value['stockWritesPerformed'] is False)
    else:
        native.require(type(value['guardsPassed']) is bool and value['stage'] in native.STAGES
                       and value['rawConfigurationPrinted'] is False and value['persistentRemoteMutations'] == 0)
        snapshot = value['unitSnapshot']
        native.require(isinstance(snapshot, dict) and set(snapshot) == set(native.empty_snapshot_diagnostics()))
        for key in ('propertiesPresent', 'missingProperties'):
            native.require(isinstance(snapshot[key], list) and len(snapshot[key]) <= len(native.PROPERTIES)
                           and all(item in native.PROPERTIES for item in snapshot[key]) and len(set(snapshot[key])) == len(snapshot[key]))
        native.require(set(snapshot['propertiesPresent']).isdisjoint(snapshot['missingProperties'])
                       and set(snapshot['propertiesPresent']) | set(snapshot['missingProperties']) == set(native.PROPERTIES)
                       and all(type(snapshot[key]) is int and 0 <= snapshot[key] <= 65536 for key in ('unknownLineCount', 'duplicatePropertyCount'))
                       and snapshot['formatCategory'] in {'VALID', 'MISSING_PROPERTIES', 'DUPLICATE_PROPERTIES', 'UNRECOGNIZED_PROPERTY_LINES', 'OVERSIZED_OUTPUT'}
                       and snapshot['commandCategory'] in {'NOT_QUERIED', 'SUCCESS', 'NONZERO_EXIT', 'TIMEOUT', 'EXECUTION_UNAVAILABLE'}
                       and (snapshot['snapshotExitCode'] is None or type(snapshot['snapshotExitCode']) is int and -255 <= snapshot['snapshotExitCode'] <= 255)
                       and (snapshot['systemdVersion'] is None or type(snapshot['systemdVersion']) is int and 100 <= snapshot['systemdVersion'] <= 9999)
                       and snapshot['versionReadCategory'] in {'NOT_QUERIED', 'SUCCESS', 'NONZERO_EXIT', 'TIMEOUT', 'EXECUTION_UNAVAILABLE', 'UNRECOGNIZED_VERSION'})
        proofs = snapshot['typedEmptyArrayProof']
        native.require(isinstance(proofs, list) and len(proofs) <= len(native.EMPTY_ARRAY_SIGNATURES)
                       and type(snapshot['missingArrayPropertiesVerifiedEmpty']) is bool)
        names = []
        for proof in proofs:
            native.require(isinstance(proof, dict) and set(proof) == {'property', 'expectedSignature', 'verifiedEmpty', 'readCategory'}
                           and proof['property'] in native.EMPTY_ARRAY_SIGNATURES and proof['property'] in snapshot['missingProperties']
                           and proof['expectedSignature'] == native.EMPTY_ARRAY_SIGNATURES[proof['property']]
                           and type(proof['verifiedEmpty']) is bool
                           and proof['readCategory'] in {'READ_UNVERIFIED', 'EMPTY_VERIFIED', 'NONEMPTY_OR_UNRECOGNIZED'}
                           and (proof['readCategory'] == 'EMPTY_VERIFIED') == proof['verifiedEmpty'])
            names.append(proof['property'])
        native.require(len(names) == len(set(names)))
        if snapshot['missingArrayPropertiesVerifiedEmpty']:
            native.require(set(names) == set(snapshot['missingProperties']) and all(proof['verifiedEmpty'] for proof in proofs))
    return value


def main():
    recover = os.environ.get('MM_RECOVER_NATIVE_POSTGRES') == '1'
    if recover:
        validate_recovery_mode(os.environ)
    arguments = ssh_arguments(os.environ)
    source = remote_source(recover) + '\n'
    source = 'try:\n' + '\n'.join('    ' + line for line in source.splitlines()) + '\nexcept Exception:\n    print("MM_NATIVE_POSTGRES_STAGE="+sys.modules["native_postgres_recovery"].STAGE,file=sys.stderr)\n    sys.exit(1)'
    result = subprocess.run(arguments + ['python3 -c ' + shlex.quote(source)], capture_output=True, text=True, timeout=300 if recover else 50, check=False)
    if result.returncode:
        match = re.search(r'^MM_NATIVE_POSTGRES_STAGE=([A-Z_]+)$', result.stderr, re.M)
        if match and match[1] in native.STAGES:
            print('Native PostgreSQL recovery stopped at ' + match[1] + '; private responses withheld.', file=sys.stderr)
        raise native.RecoveryError('Native service operation could not be verified.')
    native.require(len(result.stdout) < 4096)
    print(json.dumps(safe_report(json.loads(result.stdout), recover), sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Native PostgreSQL operation was not verified; inspect its state before retrying. Private values withheld.', file=sys.stderr)
        sys.exit(1)
