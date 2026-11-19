import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_postgres_recovery as native


SECRET = 'private-environment-value-must-stay-on-host'
UNIT = {
    'Id': 'postgresql-17.service', 'LoadState': 'loaded', 'ActiveState': 'failed', 'SubState': 'failed',
    'Result': 'oom-kill', 'ExecMainCode': '2', 'ExecMainStatus': '9', 'MainPID': '0', 'User': 'postgres', 'Group': 'postgres',
    'Environment': 'PGDATA=/private/data/', 'EnvironmentFiles': '', 'DropInPaths': '',
    'FragmentPath': '/usr/lib/systemd/system/postgresql-17.service',
    'ExecStart': '{ path=/usr/pgsql-17/bin/postgres ; argv[]=/usr/pgsql-17/bin/postgres -D ${PGDATA} ; ignore_errors=no ; pid=123 ; code=killed ; status=9 }',
    'ExecStartPre': '{ path=/usr/pgsql-17/bin/postgresql-17-check-db-dir ; argv[]=/usr/pgsql-17/bin/postgresql-17-check-db-dir ${PGDATA} ; ignore_errors=no ; pid=120 ; code=exited ; status=0 }',
    'ExecStartPost': '', 'ExecStop': '', 'ExecStopPost': '', 'ExecCondition': '',
    'InvocationID': 'a' * 32,
}


class NativePostgresRecoveryTest(unittest.TestCase):
    def test_exact_failed_service_preserves_only_private_start_identity(self):
        identity = native.validate_unit(UNIT)
        self.assertEqual(identity['data'], Path('/private/data'))
        self.assertEqual(identity['server'], ['/usr/pgsql-17/bin/postgres', '-D', '/private/data'])
        report = native.failure_report(UNIT)
        self.assertEqual(report, {'failureResult': 'oom-kill', 'execMainCode': 2, 'execMainStatus': 9})
        self.assertNotIn('/private', json.dumps(report))

    def test_service_state_or_unknown_start_hooks_fail_closed(self):
        changes = [
            {'ActiveState': 'active', 'SubState': 'running'}, {'ActiveState': 'inactive', 'SubState': 'dead'},
            {'LoadState': 'not-found'}, {'Id': 'another.service'}, {'MainPID': '25'},
            {'ExecStartPost': SECRET}, {'ExecStop': SECRET}, {'ExecStopPost': SECRET}, {'ExecCondition': SECRET},
            {'EnvironmentFiles': SECRET}, {'DropInPaths': SECRET},
            {'ExecStart': '{ path=/bin/sh ; argv[]=/bin/sh -c ' + SECRET + ' ; }'},
            {'ExecStartPre': '{ path=/usr/pgsql-17/bin/initdb ; argv[]=/usr/pgsql-17/bin/initdb ${PGDATA} ; }'},
            {'Environment': 'PGDATA=/private/data PGPORT=5433'},
        ]
        for change in changes:
            with self.subTest(change=list(change)), self.assertRaises(native.RecoveryError):
                native.validate_unit(UNIT | change)

    def test_unit_properties_are_private_and_duplicate_properties_are_rejected(self):
        raw = '\n'.join(key + '=' + value for key, value in UNIT.items())
        self.assertEqual(native.unit_properties(raw), UNIT)
        with self.assertRaises(native.RecoveryError):
            native.unit_properties(raw + '\nEnvironment=' + SECRET)

    def test_selected_empty_unit_properties_are_requested_explicitly(self):
        output = '\n'.join(key + '=' + value for key, value in UNIT.items())
        with patch.object(native, 'command', return_value=output) as run:
            self.assertEqual(native.unit_snapshot(), UNIT)
        self.assertIn('--all', run.call_args.args[0])
        selections = [arg for arg in run.call_args.args[0] if arg.startswith('--property=')]
        self.assertEqual(selections, ['--property=' + ','.join(native.PROPERTIES)])

    def test_partial_snapshot_names_explain_older_systemd_output_without_values(self):
        values, summary = native.parse_unit_snapshot('Result=signal\nExecMainCode=2\nExecMainStatus=9\nEnvironment=' + SECRET)
        self.assertEqual(summary['formatCategory'], 'MISSING_PROPERTIES')
        self.assertIn('InvocationID', summary['missingProperties'])
        self.assertIn('Result', summary['propertiesPresent'])
        self.assertEqual(native.failure_report(values)['execMainStatus'], 9)
        self.assertNotIn(SECRET, json.dumps(summary))
        with self.assertRaises(native.RecoveryError):
            native.unit_properties('Result=signal')

    def test_snapshot_unknown_keys_duplicates_and_oversize_are_fixed_categories(self):
        for output, category in [('Result=signal\nResult=' + SECRET, 'DUPLICATE_PROPERTIES'),
                                 (SECRET + '=' + SECRET, 'UNRECOGNIZED_PROPERTY_LINES'),
                                 ('x' * 65537, 'OVERSIZED_OUTPUT')]:
            _, summary = native.parse_unit_snapshot(output)
            self.assertEqual(summary['formatCategory'], category)
            self.assertNotIn(SECRET, json.dumps(summary))

    def test_systemd_version_output_returns_only_major_number(self):
        self.assertEqual(native.systemd_version('systemd 219\n' + SECRET), 219)
        with self.assertRaises(native.RecoveryError):
            native.systemd_version(SECRET)

    def test_omitted_array_properties_are_filled_only_after_exact_typed_empty_reads(self):
        partial = {key: value for key, value in UNIT.items() if key not in native.EMPTY_ARRAY_SIGNATURES}
        calls = []
        def reader(arguments, timeout):
            calls.append(arguments)
            return native.EMPTY_ARRAY_SIGNATURES[arguments[-1]] + ' 0\n'
        full = native.normalize_empty_unit_arrays(partial, reader)
        self.assertEqual(full, UNIT)
        self.assertEqual(len(calls), 5)
        for arguments in calls:
            self.assertEqual(arguments[:4], ['busctl', '--system', '--no-pager', '--timeout=5'])
            self.assertIn(native.UNIT_BUS_PATH, arguments)
            self.assertEqual(arguments[-2], 'org.freedesktop.systemd1.Service')

    def test_nonempty_wrongly_typed_and_failed_array_reads_never_normalize(self):
        partial = {key: value for key, value in UNIT.items() if key != 'ExecStop'}
        for response in ('a(sasbttttuii) 1 ' + SECRET, 'a(sb) 0', 'a(sasbttttuii) 0 ' + SECRET):
            with self.subTest(response=response[:20]), self.assertRaises(native.RecoveryError) as error:
                native.normalize_empty_unit_arrays(partial, lambda *args, **kwargs: response)
            self.assertNotIn(SECRET, str(error.exception))
        def failed(*args, **kwargs):
            raise native.RecoveryError(SECRET)
        with self.assertRaises(native.RecoveryError):
            native.normalize_empty_unit_arrays(partial, failed)

    def test_missing_scalar_or_wrong_unit_does_not_attempt_array_proof(self):
        for partial in ({key: value for key, value in UNIT.items() if key != 'InvocationID'},
                        {key: value for key, value in (UNIT | {'Id': 'other.service'}).items() if key != 'ExecStop'}):
            with patch.object(native, 'command') as reader, self.assertRaises(native.RecoveryError):
                native.normalize_empty_unit_arrays(partial, reader)
            reader.assert_not_called()

    def test_native_postmaster_alias_is_accepted_as_the_existing_server_command(self):
        values = UNIT | {'ExecStart': UNIT['ExecStart'].replace('/bin/postgres ', '/bin/postmaster ')}
        self.assertEqual(native.validate_unit(values)['server'][0], '/usr/pgsql-17/bin/postmaster')

    def test_only_the_standard_existing_postgres_child_oom_environment_is_accepted(self):
        standard = 'PGDATA=/private/data PG_OOM_ADJUST_FILE=/proc/self/oom_score_adj PG_OOM_ADJUST_VALUE=0'
        native.validate_unit(UNIT | {'Environment': standard})
        for value in (standard.replace('/proc/self/oom_score_adj', '/private/other'), standard.replace('VALUE=0', 'VALUE=-1000'),
                      'PGDATA=/private/data PG_OOM_ADJUST_VALUE=0'):
            with self.assertRaises(native.RecoveryError):
                native.validate_unit(UNIT | {'Environment': value})

    def test_cluster_requires_existing_v17_control_and_data_files(self):
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)
            data.chmod(0o700)
            (data / 'PG_VERSION').write_text('17\n')
            for name in ('base', 'global', 'pg_wal'):
                (data / name).mkdir()
            (data / 'global/pg_control').write_bytes(b'existing-control')
            native.validate_cluster(data, os.getuid())
            (data / 'PG_VERSION').write_text('16\n')
            with self.assertRaises(native.RecoveryError):
                native.validate_cluster(data, os.getuid())
            (data / 'PG_VERSION').write_text('17\n')
            (data / 'global/pg_control').unlink()
            with self.assertRaises(native.RecoveryError):
                native.validate_cluster(data, os.getuid())

    def test_effective_config_must_match_target_and_data_directory(self):
        values = {'port': '5432', 'listen_addresses': '*', 'data_directory': '/private/data'}
        native.validate_effective_config(values, Path('/private/data'))
        for change in ({'port': '5433'}, {'listen_addresses': 'localhost'}, {'data_directory': '/other/data'}):
            with self.subTest(change=list(change)), self.assertRaises(native.RecoveryError):
                native.validate_effective_config(values | change, Path('/private/data'))

    def test_failed_metadata_redacts_unrecognized_values(self):
        report = native.failure_report(UNIT | {'Result': SECRET, 'ExecMainStatus': SECRET})
        self.assertEqual(report['failureResult'], 'UNAVAILABLE')
        self.assertIsNone(report['execMainStatus'])
        self.assertNotIn(SECRET, json.dumps(report))

    def test_inspection_failure_is_sanitized_and_never_starts_a_service(self):
        with patch.object(native, 'preflight', side_effect=native.RecoveryError(SECRET)), patch.object(native, 'command') as run:
            report = native.inspect()
        self.assertFalse(report['guardsPassed'])
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertFalse(any(call.args[0][:2] == ['systemctl', 'start'] for call in run.call_args_list))

    def test_recovery_starts_once_after_rechecking_identity_and_keeps_attempt_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            context = {'identity': 'same', 'configuration': 'same', 'backendImage': native.BACKEND_IMAGE,
                       'failure': native.failure_report(UNIT), 'server': ['/usr/pgsql-17/bin/postgres', '-D', '/private/data']}
            with patch.object(native, 'preflight', return_value=context), patch.object(native, 'receipt_folder', return_value=Path(folder)), \
                    patch.object(native, 'command') as run, patch.object(native, 'wait_ready', return_value=None), \
                    patch.object(native, 'post_start_verify', return_value=None):
                result = native.recover()
                self.assertTrue(result['serviceStartedOnce'])
                self.assertEqual(run.call_args_list[0].args[0], ['systemctl', 'start', native.UNIT])
                self.assertEqual(run.call_count, 1)
                with self.assertRaises(native.RecoveryError):
                    native.recover()
                self.assertEqual(run.call_count, 1)
            self.assertTrue((Path(folder) / native.RECEIPT).exists())

    def test_changed_identity_and_failed_start_are_not_retried(self):
        context = {'identity': 'before', 'configuration': 'same', 'backendImage': native.BACKEND_IMAGE,
                   'failure': native.failure_report(UNIT), 'server': []}
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(native, 'preflight', side_effect=[context, context | {'identity': 'changed'}]), \
                    patch.object(native, 'receipt_folder', return_value=Path(folder)), patch.object(native, 'command') as run:
                with self.assertRaises(native.RecoveryError):
                    native.recover()
                run.assert_not_called()
            with patch.object(native, 'preflight', return_value=context), patch.object(native, 'receipt_folder', return_value=Path(folder)), \
                    patch.object(native, 'command', side_effect=native.RecoveryError(SECRET)) as run:
                with self.assertRaises(native.RecoveryError):
                    native.recover()
                with self.assertRaises(native.RecoveryError):
                    native.recover()
                self.assertEqual(run.call_count, 1)

    def test_ci_recovery_requires_explicit_manual_job_identity(self):
        import run_native_postgres_recovery as runner
        good = {'MM_RECOVER_NATIVE_POSTGRES': '1', 'CI_JOB_NAME': 'recover_native_postgres'}
        runner.validate_recovery_mode(good)
        for values in ({}, good | {'CI_JOB_NAME': 'read_only_diagnostics'}, good | {'MM_RECOVER_NATIVE_POSTGRES': '0'}):
            with self.assertRaises(native.RecoveryError):
                runner.validate_recovery_mode(values)

    def test_remote_inspection_source_selects_only_inspection(self):
        import run_native_postgres_recovery as runner
        source = runner.remote_source(recover=False)
        self.assertIn('native.inspect()', source)
        self.assertNotIn('native.recover()', source)

    def test_safe_transport_reports_reject_private_values_and_unknown_fields(self):
        import run_native_postgres_recovery as runner
        report = {'format': 'mm-native-postgres-preflight-v1', 'service': native.UNIT, 'guardsPassed': False, 'stage': 'CONFIGURATION',
                  'failureResult': 'oom-kill', 'execMainCode': 2, 'execMainStatus': 9, 'rawConfigurationPrinted': False, 'persistentRemoteMutations': 0}
        report['unitSnapshot'] = native.empty_snapshot_diagnostics()
        self.assertEqual(runner.safe_report(report), report)
        for changes in ({'service': SECRET}, {'stage': SECRET}, {'environment': SECRET}, {'failureResult': SECRET}):
            with self.subTest(changes=list(changes)), self.assertRaises(native.RecoveryError):
                runner.safe_report(report | changes)


if __name__ == '__main__':
    unittest.main()
