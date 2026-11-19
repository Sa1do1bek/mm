import json
import subprocess
import unittest

import projection_activity_diagnostics as activity
import read_only_diagnostics as diagnostics

SAFE = {'versions':[{'version':'8','success':True}], 'activity':[{'phase':'PROJECTING_LEDGER','state':'active','waitType':'IO','waitEvent':'DataFileRead','seconds':1200,'blockers':0}],
        'settings':{key:'on' if key.startswith('enable_') or key=='jit' else '4' for key in activity.SETTINGS},
        'collation':{'provider':'c','collate':'en_US.utf8','ctype':'en_US.utf8'}}
SECRET = 'private-credentials-and-business-record'


class ProjectionActivityDiagnosticsTest(unittest.TestCase):
    def test_payload_contains_only_migration_and_wait_metadata(self):
        self.assertEqual(activity.safe_report(SAFE),SAFE)
        for unsafe in (SAFE|{'credentials':SECRET},SAFE|{'versions':[{'version':SECRET,'success':True}]},
                       SAFE|{'activity':[SAFE['activity'][0]|{'query':SECRET}]},
                       SAFE|{'activity':[SAFE['activity'][0]|{'waitEvent':SECRET}]},
                       SAFE|{'activity':[SAFE['activity'][0]|{'seconds':SECRET}]}):
            with self.assertRaises(ValueError): activity.safe_report(unsafe)

    def test_activity_sql_is_read_only_bounded_and_never_returns_query_text(self):
        self.assertTrue(activity.SQL.startswith('BEGIN READ ONLY;'))
        self.assertIn("statement_timeout='8s'",activity.SQL)
        self.assertIn("lock_timeout='1s'",activity.SQL)
        self.assertIn('pid<>pg_backend_pid()',activity.SQL)
        self.assertNotRegex(activity.SQL,r"(?i)\b(?:INSERT|DELETE|ALTER|DROP|TRUNCATE|pg_cancel_backend|pg_terminate_backend)\b")
        self.assertNotIn("'query',query",activity.SQL)

    def test_parent_probe_does_not_disclose_raw_output_or_failure_details(self):
        calls=[]
        def valid(args,**kwargs):
            calls.append((args,kwargs))
            return subprocess.CompletedProcess(args,0,json.dumps(SAFE),SECRET)
        result=diagnostics.projection_activity_probe(['ssh'],valid)
        self.assertEqual(result['data'],SAFE)
        self.assertEqual(result['databaseMutations'],0)
        self.assertNotIn(SECRET,json.dumps(result))
        self.assertEqual(calls[0][1]['timeout'],50)
        for code,payload in ((1,SECRET),(0,SECRET),(0,json.dumps(SAFE|{'secret':SECRET}))):
            result=diagnostics.projection_activity_probe(['ssh'],lambda args,**kwargs:subprocess.CompletedProcess(args,code,payload,SECRET))
            self.assertEqual(result,{'responseValidated':False})
            self.assertNotIn(SECRET,json.dumps(result))


if __name__=='__main__': unittest.main()
