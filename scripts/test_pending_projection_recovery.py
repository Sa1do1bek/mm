import copy
import unittest
from unittest.mock import patch
import pending_projection_recovery as recovery

HISTORY=[{'version':str(i),'type':'SQL','script':f'V{i}__reviewed.sql','checksum':i} for i in range(1,9)]
PAIR={'pid':42,'started':'2026-10-11 17:26:20.123+00','hash':'a'*32,'seconds':1000}
STATE={'history':[row|{'success':True} for row in HISTORY],'projectionAbsent':True,'pairing':[PAIR],
       'stocks':{table:{'rows':3,'md5':'b'*32} for table in recovery.STOCK_TABLES},'archiveRuns':'c'*32,'archiveChunks':'d'*32}


class PendingProjectionRecoveryTest(unittest.TestCase):
    def test_only_uncommitted_v9_with_exact_v8_prefix_can_be_cancelled(self):
        recovery.validate_state(STATE,HISTORY,pending=True)
        for change in ({'history':STATE['history']+[{'version':'9','success':True}]}, {'projectionAbsent':False},
                       {'pairing':[]},{'pairing':[PAIR,PAIR]},{'pairing':[PAIR|{'seconds':5}]},
                       {'pairing':[PAIR|{'started':"';DELETE FROM stocks;--"}]}):
            with self.assertRaises(ValueError):recovery.validate_state(STATE|change,HISTORY,pending=True)

    def test_complete_rollback_is_required_before_previous_image_is_allowed(self):
        recovery.validate_state(STATE|{'pairing':[]},HISTORY,pending=False)
        for state in (STATE,STATE|{'projectionAbsent':False,'pairing':[]}):
            with self.assertRaises(ValueError):recovery.validate_state(state,HISTORY,pending=False)

    def test_cancel_rechecks_pid_start_query_hash_role_and_version(self):
        sql=recovery.cancel_sql(PAIR)
        for guard in ('pid=42',"query_start='2026-10-11 17:26:20.123+00'::timestamptz", "md5(query)='"+'a'*32+"'",
                      'usename=current_user','datname=current_database()',"state='active'","version='9'",
                      'o.document_key=h.document_key','o.material_source_id=h.material_source_id'):
            self.assertIn(guard,sql)
        self.assertNotIn('pg_terminate_backend',sql)
        for item in (PAIR|{'pid':'42;DROP'},PAIR|{'hash':"';DROP"},PAIR|{'started':"';DROP"}):
            with self.assertRaises(ValueError):recovery.cancel_sql(item)

    def test_stock_and_archive_fingerprints_must_match(self):
        recovery.unchanged(STATE,STATE|{'pairing':[]})
        for key in ('stocks','archiveRuns','archiveChunks','history'):
            changed=copy.deepcopy(STATE)
            changed[key]={} if isinstance(changed[key],dict) else 'changed'
            with self.assertRaises(ValueError):recovery.unchanged(STATE,changed)

    def test_fingerprint_sql_is_bounded_read_only_and_covers_all_stock_tables(self):
        self.assertTrue(recovery.STATE_SQL.startswith('BEGIN READ ONLY;'))
        self.assertIn("statement_timeout='25s'",recovery.STATE_SQL)
        for table in recovery.STOCK_TABLES:self.assertIn('FROM '+table+' t',recovery.STATE_SQL)
        self.assertNotRegex(recovery.STATE_SQL,r'(?i)\b(?:INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE|pg_cancel_backend)\b(?! warehouse_imported_movements)')

    def test_healthy_application_is_never_interrupted(self):
        with patch.object(recovery,'healthy',return_value=True), patch.object(recovery,'private_context') as context, patch.object(recovery,'run') as run:
            with self.assertRaises(ValueError):recovery.recover('a'*64,'b'*64)
            context.assert_not_called();run.assert_not_called()


if __name__=='__main__':unittest.main()
