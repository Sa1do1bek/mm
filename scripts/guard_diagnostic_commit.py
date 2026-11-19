"""Allow reviewed diagnostic/manual-recovery tooling; reject application files."""
import os
import re
import subprocess
import sys

ALLOWED = {'.gitlab-ci.yml', 'scripts/read_only_diagnostics.py', 'scripts/test_read_only_diagnostics.py',
           'scripts/projection_activity_diagnostics.py', 'scripts/test_projection_activity_diagnostics.py',
           'scripts/guard_diagnostic_commit.py', 'scripts/test_guard_diagnostic_commit.py',
           'scripts/pending_projection_recovery.py','scripts/test_pending_projection_recovery.py',
           'scripts/run_pending_projection_recovery.py', 'scripts/native_postgres_recovery.py',
           'scripts/test_native_postgres_recovery.py', 'scripts/run_native_postgres_recovery.py'}


def validate(paths):
    if not paths or set(paths)-ALLOWED:
        raise ValueError('Diagnostic commit includes unsupported changes; no SSH probe or deployment is allowed.')


def guard(env=os.environ):
    if env.get('CI_PIPELINE_SOURCE') in {'web','api'}:
        return
    if not (env.get('CI_PIPELINE_SOURCE')=='push' and env.get('MM_DIAGNOSTIC_COMMIT')=='1'
            and env.get('CI_COMMIT_REF_PROTECTED')=='true'
            and env.get('CI_COMMIT_BRANCH')==env.get('CI_DEFAULT_BRANCH')=='main'
            and env.get('CI_COMMIT_TITLE','').startswith('[mm-diagnostics]')):
        raise ValueError('Unsupported diagnostic pipeline source.')
    base,head=env.get('CI_COMMIT_BEFORE_SHA',''),env.get('CI_COMMIT_SHA','')
    if not all(re.fullmatch('[a-f0-9]{40}',value) and value!='0'*40 for value in (base,head)):
        raise ValueError('Diagnostic commit comparison is unavailable.')
    result=subprocess.run(['git','diff','--name-only',base,head],capture_output=True,text=True,timeout=10,check=False)
    if result.returncode:
        raise ValueError('Diagnostic commit comparison failed.')
    validate(result.stdout.splitlines())


if __name__=='__main__':
    try:
        guard()
        print('Tooling commit scope verified; application files are excluded. Recovery requires its separate manual job and runtime guards.')
    except Exception:
        print('Diagnostic scope check failed; no server action is allowed.',file=sys.stderr)
        sys.exit(1)
