import unittest
from unittest.mock import patch
import subprocess
import guard_diagnostic_commit as scope


class DiagnosticCommitScopeTest(unittest.TestCase):
    def test_application_or_deployment_changes_are_rejected(self):
        scope.validate(['scripts/read_only_diagnostics.py','.gitlab-ci.yml'])
        for paths in ([],['src/main/resources/db/migration/V9.sql'],['scripts/predeploy_backup.py'],['.gitlab-ci.yml','Dockerfile']):
            with self.assertRaises(ValueError):scope.validate(paths)

    def test_only_protected_marked_main_pushes_are_accepted(self):
        env={'CI_PIPELINE_SOURCE':'push','MM_DIAGNOSTIC_COMMIT':'1','CI_COMMIT_REF_PROTECTED':'true',
             'CI_COMMIT_BRANCH':'main','CI_DEFAULT_BRANCH':'main','CI_COMMIT_TITLE':'[mm-diagnostics] Check planner settings',
             'CI_COMMIT_BEFORE_SHA':'a'*40,'CI_COMMIT_SHA':'b'*40}
        with patch.object(scope.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'scripts/projection_activity_diagnostics.py\n','')) as run:
            scope.guard(env)
            self.assertEqual(run.call_args.args[0],['git','diff','--name-only','a'*40,'b'*40])
        for key,value in (('CI_COMMIT_REF_PROTECTED','false'),('CI_COMMIT_BRANCH','feature'),('CI_COMMIT_BEFORE_SHA','0'*40),('CI_COMMIT_TITLE','ordinary commit')):
            with self.assertRaises(ValueError):scope.guard(env|{key:value})


if __name__=='__main__':unittest.main()
