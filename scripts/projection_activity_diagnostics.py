"""Fixed read-only migration probes; execute on the deployment host, not in CI.

Connection values remain on that host. Only migration version, numeric query
duration and PostgreSQL wait/phase labels can be returned. No raw SQL is emitted.
"""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

CONTAINER = 'materials-management-backend'
IMAGE_ID = 'sha256:12d97b7eb23c343b983ee786be0ebdaad5d71fb4bb97fb549513fc5b1f1865db'
BACKUP = '.local/share/mm-predeploy/mm-before-47742-129977'
SETTINGS = {'enable_indexscan','enable_bitmapscan','enable_seqscan','enable_presorted_aggregate',
            'random_page_cost','seq_page_cost','cpu_tuple_cost','cpu_index_tuple_cost','work_mem','shared_buffers','jit','jit_above_cost'}
SQL = """BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SET LOCAL lock_timeout='1s';
SELECT json_build_object(
 'settings',(SELECT json_object_agg(name,setting) FROM pg_settings WHERE name IN ('enable_indexscan','enable_bitmapscan','enable_seqscan','enable_presorted_aggregate','random_page_cost','seq_page_cost','cpu_tuple_cost','cpu_index_tuple_cost','work_mem','shared_buffers','jit','jit_above_cost')),
 'collation',(SELECT json_build_object('provider',datlocprovider,'collate',datcollate,'ctype',datctype) FROM pg_database WHERE datname=current_database()),
 'versions', (SELECT coalesce(json_agg(json_build_object('version',version,'success',success) ORDER BY installed_rank),'[]'::json) FROM flyway_schema_history WHERE version IN ('8','9')),
 'activity', (SELECT coalesce(json_agg(json_build_object(
  'phase',CASE WHEN query ILIKE '%WITH legs AS (%' THEN 'PROJECTING_LEDGER'
               WHEN query ILIKE '%UPDATE warehouse_imported_movements%' THEN 'PAIRING_WAREHOUSES'
               WHEN query ILIKE '%CREATE INDEX warehouse_imported%' THEN 'BUILDING_INDEXES'
               WHEN query ILIKE '%warehouse_history_projection_state%' THEN 'STOCK_FINGERPRINT'
               ELSE 'OTHER' END,
  'state',state,'waitType',wait_event_type,'waitEvent',wait_event,
  'seconds',greatest(0,extract(epoch from clock_timestamp()-query_start))::integer,
  'blockers',cardinality(pg_blocking_pids(pid)))), '[]'::json)
 FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active')
);
COMMIT;"""


def require(condition):
    if not condition:
        raise ValueError('Unsupported migration probe configuration; values withheld.')


def safe_report(raw):
    require(set(raw) == {'versions','activity','settings','collation'})
    require(set(raw['settings'])==SETTINGS and all(isinstance(value,str) and re.fullmatch(r'(?:on|off|-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)',value) for value in raw['settings'].values()))
    collation=raw['collation']
    require(set(collation)=={'provider','collate','ctype'} and collation['provider'] in {'c','i','d'})
    require(all(isinstance(collation[key],str) and re.fullmatch(r'[A-Za-z0-9_.@-]{1,80}',collation[key]) for key in ('collate','ctype')))
    require(isinstance(raw['versions'],list) and len(raw['versions']) <= 2)
    require(isinstance(raw['activity'],list) and len(raw['activity']) <= 100)
    for item in raw['versions']:
        require(set(item)=={'version','success'} and item['version'] in ('8','9') and type(item['success']) is bool)
    for item in raw['activity']:
        require(set(item)=={'phase','state','waitType','waitEvent','seconds','blockers'})
        require(item['phase'] in {'PROJECTING_LEDGER','PAIRING_WAREHOUSES','BUILDING_INDEXES','STOCK_FINGERPRINT','OTHER'} and item['state']=='active')
        require(item['waitType'] is None or item['waitType'] in {'Activity','BufferPin','Client','Extension','IO','IPC','Lock','LWLock','Timeout'})
        require(item['waitEvent'] is None or re.fullmatch(r'[A-Za-z][A-Za-z0-9]{0,79}',item['waitEvent']))
        require(all(type(item[key]) is int and 0<=item[key]<=10**9 for key in ('seconds','blockers')))
    return raw


def run(args, *, timeout=12):
    result=subprocess.run(args,capture_output=True,timeout=timeout,check=False)
    require(result.returncode==0)
    return result.stdout


def private_context(helper_sha, configuration_sha):
    sys.dont_write_bytecode=True
    folder=Path.home()/BACKUP
    require(folder.is_dir() and not folder.is_symlink() and not folder.stat().st_mode & 0o077)
    helper=folder/'scripts/predeploy_backup.py'
    configuration=folder/'src/main/resources/application-prod.yml'
    require(hashlib.sha256(helper.read_bytes()).hexdigest()==helper_sha)
    require(hashlib.sha256(configuration.read_bytes()).hexdigest()==configuration_sha)
    spec=importlib.util.spec_from_file_location('private_predeploy_helper',helper)
    backup=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    inspected=json.loads(run(['docker','inspect',CONTAINER]))[0]
    require(inspected['Image']==IMAGE_ID and inspected['State']['Running'] is True)
    jar=run(['docker','exec',CONTAINER,'cat','/app/app.jar'],timeout=20)
    require(len(jar)<=300*1024*1024)
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        def content(name):
            key='BOOT-INF/classes/'+name
            return archive.read(key).decode() if key in archive.namelist() else ''
        connection=backup.runtime_configuration(inspected,configuration.read_text(),content('application-prod.yml'),content('application.yml'),[content('application.properties'),content('application-prod.properties')])
    verified=json.loads((folder/'backup-verified.json').read_text())
    major=verified['postgresMajor']
    require(type(major) is int and 12<=major<=18)
    return backup,folder,inspected,connection,verified


def collect(helper_sha, configuration_sha):
    backup,folder,inspected,connection,verified=private_context(helper_sha,configuration_sha)
    major=verified['postgresMajor']
    with tempfile.TemporaryDirectory(prefix='mm-migration-probe-') as temporary:
        directory=Path(temporary)
        backup.private_write(directory/'.pgpass',backup.pgpass(connection))
        command=backup.client_command(CONTAINER,directory,connection,'postgres:'+str(major)+'-alpine','psql',
            '-h',connection['host'],'-p',str(connection['port']),'-U',connection['username'],'-d',connection['database'],
            '--no-psqlrc','--no-password','-qAt','-v','ON_ERROR_STOP=1','-c',SQL)
        command.insert(3,'--read-only')
        payload=run(command,timeout=20)
        require(len(payload)<16384)
        return safe_report(json.loads(payload))


if __name__=='__main__':
    try:
        require(len(sys.argv)==3 and all(re.fullmatch('[a-f0-9]{64}',arg) for arg in sys.argv[1:]))
        print(json.dumps(collect(*sys.argv[1:]),sort_keys=True))
    except Exception:
        print('Migration activity probe failed; connection values and raw responses withheld.',file=sys.stderr)
        sys.exit(1)
