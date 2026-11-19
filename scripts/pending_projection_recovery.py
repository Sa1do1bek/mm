"""One guarded recovery of the uncommitted V9 deployment; run on the host only.

Never restore the database, alter Flyway history, or write operational stock.
Cancel only the identified pairing query, verify transactional rollback, then
start the exact previously backed-up image against its unchanged V8 database.
"""
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib import request
import zipfile

from projection_activity_diagnostics import CONTAINER, IMAGE_ID, private_context, require, run

COMPANY='753e618e-8d47-4efd-a41e-df8311b83d2f'
FAILED=CONTAINER+'-pending-v9-47742'
STAGE='PREFLIGHT'
STOCK_TABLES=('stock_balances','stock_batches','inventory_cost_layers','stock_cost_movements',
              'stock_movements','stock_movement_allocations','stock_movement_allocation_cost_layers')
PAIRING="query ILIKE '%UPDATE warehouse_imported_movements h SET target_warehouse_id=(%' AND query ILIKE '%o.document_key=h.document_key%' AND query ILIKE '%o.material_source_id=h.material_source_id%'"
ACTIVE=f"datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND ({PAIRING})"
STATE_SQL="""BEGIN READ ONLY; SET LOCAL statement_timeout='25s'; SET LOCAL lock_timeout='1s';
SELECT json_build_object(
 'history',(SELECT json_agg(json_build_object('version',version,'type',type,'script',script,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM public.flyway_schema_history),
 'projectionAbsent',to_regclass('public.warehouse_imported_movements') IS NULL AND to_regclass('public.warehouse_history_projection_state') IS NULL,
 'pairing',(SELECT coalesce(json_agg(json_build_object('pid',pid,'started',query_start,'hash',md5(query),'seconds',extract(epoch from clock_timestamp()-query_start)::integer)),'[]'::json) FROM pg_stat_activity WHERE %s),
 'stocks',json_build_object(%s),
 'archiveRuns',(SELECT md5(coalesce(string_agg(md5(row_to_json(t)::text),'' ORDER BY id),'')) FROM warehouse_history_runs t WHERE tenant_id='default' AND company_id='%s'),
 'archiveChunks',(SELECT md5(coalesce(string_agg(md5(row_to_json(t)::text),'' ORDER BY import_id,first_seq),'')) FROM warehouse_history_chunks t WHERE import_id IN (SELECT id FROM warehouse_history_runs WHERE tenant_id='default' AND company_id='%s'))
); COMMIT;""" % (ACTIVE, ','.join("'%s',(SELECT json_build_object('rows',count(*),'md5',md5(coalesce(string_agg(md5(row_to_json(t)::text),'' ORDER BY id),''))) FROM %s t WHERE tenant_id='default' AND company_id='%s')" % (tab,tab,COMPANY) for tab in STOCK_TABLES), COMPANY, COMPANY)


def validate_state(state, expected_history, *, pending):
    require(isinstance(state,dict) and set(state)=={'history','projectionAbsent','pairing','stocks','archiveRuns','archiveChunks'})
    require(state['history']==[row|{'success':True} for row in expected_history])
    require([row['version'] for row in expected_history]==[str(i) for i in range(1,9)])
    require(state['projectionAbsent'] is True and set(state['stocks'])==set(STOCK_TABLES))
    for item in state['stocks'].values():
        require(set(item)=={'rows','md5'} and type(item['rows']) is int and item['rows']>=0 and re.fullmatch('[a-f0-9]{32}',item['md5']))
    require(all(re.fullmatch('[a-f0-9]{32}',state[key]) for key in ('archiveRuns','archiveChunks')))
    require(len(state['pairing'])==(1 if pending else 0))
    if pending:
        item=state['pairing'][0]
        require(set(item)=={'pid','started','hash','seconds'} and type(item['pid']) is int and item['pid']>1
                and re.fullmatch(r'[0-9T:.+ -]{19,40}',item['started']) and re.fullmatch('[a-f0-9]{32}',item['hash'])
                and type(item['seconds']) is int and item['seconds']>300)


def unchanged(before,after):
    require(all(before[key]==after[key] for key in ('history','stocks','archiveRuns','archiveChunks')))


def cancel_sql(item):
    # Numeric PID, timestamp and query hash are rechecked in PostgreSQL to avoid
    # cancelling a reused PID or a query that changed between probes.
    require(type(item['pid']) is int and item['pid']>1 and re.fullmatch(r'[0-9T:.+ -]{19,40}',item['started']) and re.fullmatch('[a-f0-9]{32}',item['hash']))
    return "SET statement_timeout='5s'; SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE %s AND pid=%d AND query_start='%s'::timestamptz AND md5(query)='%s' AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='9');" % (ACTIVE,item['pid'],item['started'],item['hash'])


def sql_client(backup,directory,connection,major,sql):
    args=backup.client_command(CONTAINER,directory,connection,'postgres:'+str(major)+'-alpine','psql',
        '-h',connection['host'],'-p',str(connection['port']),'-U',connection['username'],'-d',connection['database'],
        '--no-psqlrc','--no-password','-qAt','-v','ON_ERROR_STOP=1','-c',sql)
    # The DB endpoint was resolved privately and this path must keep working
    # after the failed app stops. Validate host-network connectivity first.
    args[args.index('container:'+CONTAINER)]='host'
    args.insert(3,'--read-only')
    return run(args,timeout=40).decode().strip()


def healthy():
    class NoRedirect(request.HTTPRedirectHandler):
        def redirect_request(self,*args):return None
    try:
        opener=request.build_opener(request.ProxyHandler({}),NoRedirect())
        with opener.open('http://127.0.0.1:10320/actuator/health/deployment',timeout=3) as response:
            return response.status==200 and json.load(response).get('status')=='UP'
    except Exception:return False


def validate_previous(backup,folder,verified,connection):
    old=json.loads((folder/'container-inspect.json').read_text())
    require(old['Image']==verified['oldImageId'] and old['Image']!=IMAGE_ID)
    require(re.fullmatch(r'[^\s]+:[a-f0-9]{40}',old['Config']['Image']))
    image=json.loads(run(['docker','image','inspect',old['Image']]))[0]
    require(image['Id']==old['Image'])
    require(old['HostConfig']['NetworkMode'] in ('default','bridge'))
    require(old['HostConfig']['PortBindings']=={'8080/tcp':[{'HostIp':'','HostPort':'10320'}]})
    require(len(old['Mounts'])==1 and old['Mounts'][0]['Type']=='volume'
            and old['Mounts'][0]['Name']=='materials-management-attachments'
            and old['Mounts'][0]['Destination']=='/app/data/attachments' and old['Mounts'][0]['RW'] is True)
    require(backup.checksum(folder/'deployment.env')==verified['deploymentEnvSnapshotSha256'])
    backup.validate_environment_snapshot(folder/'deployment.env',folder/'deployment-env-state.txt')
    env=backup.environment(old['Config'])
    require(all(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*',key) and not any(c in value for c in '\r\n\x00') for key,value in env.items()))
    # Verify the backed-up running JAR against the still-local immutable image.
    # This temporary container is never started and has no network.
    temporary=run(['docker','create','--network','none',old['Image']]).decode().strip()
    require(re.fullmatch('[a-f0-9]{64}',temporary))
    try:
        with tempfile.TemporaryDirectory(prefix='mm-previous-image-') as temp:
            jar=Path(temp)/'app.jar'
            run(['docker','cp',temporary+':/app/app.jar',str(jar)],timeout=20)
            os.chmod(jar,0o600)
            require(backup.checksum(jar)==verified['oldApplicationJarSha256'])
            with zipfile.ZipFile(jar) as archive:
                def content(name):
                    key='BOOT-INF/classes/'+name
                    return archive.read(key).decode() if key in archive.namelist() else ''
                prod=(folder/'src/main/resources/application-prod.yml').read_text()
                require(backup.runtime_configuration(old,prod,content('application-prod.yml'),content('application.yml'),[content('application.properties'),content('application-prod.properties')])==connection)
                backup.validate_deployment_configuration(old,image,folder/'deployment.env',prod,content('application.yml'),connection)
                migrations=json.loads((folder/'reviewed-migrations.json').read_text())
                for version in range(1,9):
                    item=migrations[str(version)]
                    require(backup.flyway_checksum(archive.read('BOOT-INF/classes/db/migration/'+item['script']))==item['checksum'])
                require(not any(re.search(r'/V9__',name) for name in archive.namelist()))
    finally:
        run(['docker','rm',temporary])
    return old,env


def recover(helper_sha,configuration_sha):
    global STAGE
    require(not healthy())
    backup,folder,current,connection,verified=private_context(helper_sha,configuration_sha)
    require(current['Config']['Image'].endswith(':2b37d0aee264702f4a3f7e66625c2238b7769618') and current['RestartCount']==0)
    require(current['HostConfig']['RestartPolicy']['Name']=='unless-stopped')
    require(not (folder/'v9-recovery-before.json').exists() and not (folder/'v9-recovery-complete.json').exists())
    expected=json.loads((folder/'database-preflight.json').read_text())['history']
    STAGE='PREVIOUS_IMAGE_VALIDATION'
    old,env=validate_previous(backup,folder,verified,connection)
    with tempfile.TemporaryDirectory(prefix='mm-v9-recovery-') as temp:
        directory=Path(temp)
        backup.private_write(directory/'.pgpass',backup.pgpass(connection))
        def state():return json.loads(sql_client(backup,directory,connection,verified['postgresMajor'],STATE_SQL))
        STAGE='DATABASE_GUARD'
        before=state(); validate_state(before,expected,pending=True)
        backup.json_write(folder/'v9-recovery-before.json',before)
        run(['docker','update','--restart=no',CONTAINER])
        STAGE='GUARDED_QUERY_CANCEL'
        cancelled=sql_client(backup,directory,connection,verified['postgresMajor'],cancel_sql(before['pairing'][0]))
        if cancelled=='':
            # The guarded SELECT matched no backend; nothing was cancelled.
            run(['docker','update','--restart=unless-stopped',CONTAINER])
        require(cancelled=='t')
        # Once cancelled, do not re-enable the broken migration automatically.
        after=None
        STAGE='ROLLBACK_VERIFICATION'
        for _ in range(20):
            candidate=state()
            if not candidate['pairing']:
                validate_state(candidate,expected,pending=False); unchanged(before,candidate)
                after=candidate; break
            time.sleep(1)
        require(after is not None)
        run(['docker','stop','--time','20',CONTAINER],timeout=30)
        # A rollback is confirmed before the previous image may start.
        final=state(); validate_state(final,expected,pending=False); unchanged(before,final)
        STAGE='PREVIOUS_IMAGE_START'
        run(['docker','rename',CONTAINER,FAILED])
        backup.private_write(directory/'previous.env',''.join(key+'='+value+'\n' for key,value in env.items()))
        run(['docker','run','-d','--name',CONTAINER,'--restart','unless-stopped','-p','10320:8080',
             '--env-file',str(directory/'previous.env'),'-v','materials-management-attachments:/app/data/attachments',old['Image']])
        for _ in range(45):
            if healthy():break
            time.sleep(2)
        require(healthy())
        STAGE='RECOVERED_STOCK_VERIFICATION'
        recovered=state(); validate_state(recovered,expected,pending=False); unchanged(before,recovered)
        inspected=json.loads(run(['docker','inspect',CONTAINER]))[0]
        require(inspected['Image']==old['Image'] and inspected['State']['Running'] is True)
        backup.json_write(folder/'v9-recovery-complete.json',{'before':before,'after':recovered,'oldImageId':old['Image'],'healthy':True})
    return {'format':'mm-pending-v9-recovery-v1','previousImageHealthy':True,'migration9RolledBack':True,
            'schemaVersion':8,'stockFingerprintsUnchanged':True,'archiveRunAndChunkFingerprintsUnchanged':True,
            'failedContainerPreserved':True,'databaseRestorePerformed':False,'stockWritesPerformed':False}


if __name__=='__main__':
    try:
        require(len(sys.argv)==3 and all(re.fullmatch('[a-f0-9]{64}',arg) for arg in sys.argv[1:]))
        print(json.dumps(recover(*sys.argv[1:]),sort_keys=True))
    except Exception:
        print('MM_RECOVERY_STAGE='+STAGE,file=sys.stderr)
        sys.exit(1)
