"""Manual protected-main repair runner. Connection values stay on the host."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import re
import subprocess
import sys
from read_only_diagnostics import ssh_arguments,require


def main():
    require(os.environ.get('MM_RECOVER_PENDING_PROJECTION')=='1')
    arguments=ssh_arguments(os.environ)
    root=Path(__file__).resolve().parents[1]
    activity=(root/'scripts/projection_activity_diagnostics.py').read_text()
    recovery=(root/'scripts/pending_projection_recovery.py').read_text()
    source='import types,sys\nm=types.ModuleType("projection_activity_diagnostics")\nsys.modules[m.__name__]=m\nexec('+repr(activity)+',m.__dict__)\nexec('+repr(recovery)+')'
    hashes=[hashlib.sha256(path.read_bytes()).hexdigest() for path in (root/'scripts/predeploy_backup.py',root/'src/main/resources/application-prod.yml')]
    result=subprocess.run(arguments+['python3 -c '+shlex.quote(source)+' '+' '.join(hashes)],capture_output=True,text=True,timeout=240,check=False)
    if result.returncode:
        match=re.search(r'^MM_RECOVERY_STAGE=(PREFLIGHT|PREVIOUS_IMAGE_VALIDATION|DATABASE_GUARD|GUARDED_QUERY_CANCEL|ROLLBACK_VERIFICATION|PREVIOUS_IMAGE_START|RECOVERED_STOCK_VERIFICATION)$',result.stderr,re.M)
        if match:print('Guarded recovery stopped at '+match[1]+'. Private responses were withheld.',file=sys.stderr)
    require(result.returncode==0 and len(result.stdout)<2048)
    safe=json.loads(result.stdout)
    expected={'format':'mm-pending-v9-recovery-v1','previousImageHealthy':True,'migration9RolledBack':True,'schemaVersion':8,
              'stockFingerprintsUnchanged':True,'archiveRunAndChunkFingerprintsUnchanged':True,'failedContainerPreserved':True,
              'databaseRestorePerformed':False,'stockWritesPerformed':False}
    require(safe==expected)
    print(json.dumps(safe,sort_keys=True))


if __name__=='__main__':
    try:main()
    except Exception:
        print('Guarded recovery could not be verified. Private server values were withheld; inspect recovery state before retrying.',file=sys.stderr)
        sys.exit(1)
