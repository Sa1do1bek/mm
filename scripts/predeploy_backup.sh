#!/bin/sh
# Existing GitLab SSH variables only. No credential values are printed or stored in CI artifacts.
set -eu
umask 077

: "${SSH_PRIVATE_KEY:?Missing required SSH file variable}" "${SSH_PORT_TENZOR:?Missing SSH port}" "${SSH_USER:?Missing SSH user}" \
  "${SERVER_HOST_TENZOR:?Missing SSH host}" "${SERVICE_NAME:?Missing service name}" "${CI_PIPELINE_ID:?Missing pipeline id}" \
  "${CI_JOB_ID:?Missing job id}" "${CI_COMMIT_SHA:?Missing commit id}" "${CI_REGISTRY_IMAGE:?Missing image repository}" \
  "${REMOTE_ENV_FILE:?Missing deployment env-file path}"
for value in "$CI_PIPELINE_ID" "$CI_JOB_ID" "$SSH_PORT_TENZOR"; do
    case "$value" in *[!0-9]*|'') echo "Invalid deployment identifiers." >&2; exit 1;; esac
done
case "$SSH_USER" in *[!A-Za-z0-9_.-]*|'') echo "Unsupported SSH user." >&2; exit 1;; esac
case "$SERVER_HOST_TENZOR" in *[!A-Za-z0-9.-]*|'') echo "Unsupported SSH host." >&2; exit 1;; esac
case "$CI_REGISTRY_IMAGE" in *[!A-Za-z0-9._:/-]*|'') echo "Unsupported image repository." >&2; exit 1;; esac
case "$SERVICE_NAME" in *[!A-Za-z0-9_.-]*|'') echo "Invalid backend container name." >&2; exit 1;; esac
case "$REMOTE_ENV_FILE" in /*) ;; *) echo "Deployment env-file path must be absolute." >&2; exit 1;; esac
case "$REMOTE_ENV_FILE" in *[!A-Za-z0-9_./-]*) echo "Unsupported deployment env-file path." >&2; exit 1;; esac
case "$CI_COMMIT_SHA" in *[!a-f0-9]*|'') echo "Invalid commit image identity." >&2; exit 1;; esac
[ "${#CI_COMMIT_SHA}" -eq 40 ] || exit 1

mode=${1:-}
relative_dir=".local/share/mm-predeploy/mm-before-${CI_PIPELINE_ID}-${CI_JOB_ID}"
target="${SSH_USER}@${SERVER_HOST_TENZOR}"
chmod 600 "$SSH_PRIVATE_KEY"

remote() {
    ssh -i "$SSH_PRIVATE_KEY" -o StrictHostKeyChecking=no -p "$SSH_PORT_TENZOR" "$target" "$1"
}

if [ "$mode" = backup ]; then
    archive=$(mktemp)
    trap 'rm -f "$archive"' EXIT HUP INT TERM
    tar -cf "$archive" scripts/predeploy_backup.py scripts/stage_warehouse_projection.py src/main/resources/application-prod.yml src/main/resources/db/migration
    # The task directory is deliberately new; retrying uses a new GitLab job id.
    remote "set -eu; umask 077; mkdir -p \"\$HOME/.local/share/mm-predeploy\"; chmod 700 \"\$HOME/.local/share/mm-predeploy\"; mkdir \"\$HOME/$relative_dir\"; chmod 700 \"\$HOME/$relative_dir\""
    ssh -i "$SSH_PRIVATE_KEY" -o StrictHostKeyChecking=no -p "$SSH_PORT_TENZOR" "$target" \
        "set -eu; umask 077; tar -xf - -C \"\$HOME/$relative_dir\"; find \"\$HOME/$relative_dir\" -type f -exec chmod 600 {} +" < "$archive"
elif [ "$mode" != health ] && [ "$mode" != project ]; then
    echo "Expected backup, project or health deployment check." >&2; exit 1
fi

# Host Python is preferred. The fallback uses official Python and Docker CLI images,
# the existing Docker socket, and the same uid/gid; it installs no host packages.
remote "set -eu; umask 077; taskdir=\"\$HOME/$relative_dir\"; helper=\"\$taskdir/scripts/predeploy_backup.py\"; test -f \"\$helper\";
if [ '$mode' = backup ]; then
    snapshot=\"\$taskdir/deployment.env\"; state=\"\$taskdir/deployment-env-state.txt\";
    parent=\$(dirname '$REMOTE_ENV_FILE');
    ancestor=\"\$parent\";
    while [ ! -e \"\$ancestor\" ]; do
        test ! -L \"\$ancestor\" || { echo 'Dangling deployment env-file parent symlink is unsupported.' >&2; exit 1; };
        previous=\$(dirname \"\$ancestor\"); test \"\$previous\" != \"\$ancestor\" || exit 1; ancestor=\"\$previous\";
    done;
    test -d \"\$ancestor\" && test -x \"\$ancestor\" || { echo 'Deployment env-file parent is inaccessible; stop before replacement.' >&2; exit 1; };
    test ! -L '$REMOTE_ENV_FILE' || { echo 'Symlink deployment env-file is unsupported.' >&2; exit 1; };
    if [ -e '$REMOTE_ENV_FILE' ]; then
        test -f '$REMOTE_ENV_FILE' && test -r '$REMOTE_ENV_FILE' || { echo 'Deployment env-file is unreadable or unsupported.' >&2; exit 1; };
        cp '$REMOTE_ENV_FILE' \"\$snapshot\"; chmod 600 \"\$snapshot\"; printf 'present\\n' > \"\$state\";
    else
        : > \"\$snapshot\"; printf 'absent\\n' > \"\$state\";
    fi;
    chmod 600 \"\$snapshot\" \"\$state\";
fi;
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; sys.exit(sys.version_info < (3,9))'; then
    runner=host;
else
    runner=container;
    docker pull python:3.12-alpine >/dev/null 2>&1;
    docker pull docker:27-cli >/dev/null 2>&1;
    if [ ! -f \"\$taskdir/docker\" ]; then
        cli_container=\$(docker create docker:27-cli);
        trap 'docker rm \"\$cli_container\" >/dev/null 2>&1 || true' EXIT;
        docker cp \"\$cli_container:/usr/local/bin/docker\" \"\$taskdir/docker\" >/dev/null;
        chmod 700 \"\$taskdir/docker\";
        docker rm \"\$cli_container\" >/dev/null; trap - EXIT;
    fi;
fi;
if [ '$mode' = backup ]; then
    set -- backup --container '$SERVICE_NAME' --directory \"\$taskdir\" --configuration \"\$taskdir/src/main/resources/application-prod.yml\" --precision-migration \"\$taskdir/src/main/resources/db/migration/V6__stock_quantity_precision.sql\" --deployment-env-file \"\$taskdir/deployment.env\" --deployment-env-state-file \"\$taskdir/deployment-env-state.txt\";
elif [ '$mode' = project ]; then
    test -f \"\$taskdir/backup-verified.json\";
    helper=\"\$taskdir/scripts/stage_warehouse_projection.py\";
    set -- --container '$SERVICE_NAME' --directory \"\$taskdir\" --configuration \"\$taskdir/src/main/resources/application-prod.yml\" --image '${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}';
else
    test -f \"\$taskdir/backup-verified.json\";
    set -- health --container '$SERVICE_NAME' --image '${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}' --timeout 600;
fi;
if [ \"\$runner\" = host ]; then
    python3 \"\$helper\" \"\$@\";
else
    socket_group=\$(stat -c %g /var/run/docker.sock);
    backup_root=\"\$HOME/.local/share/mm-predeploy\";
    docker run --rm --user \"\$(id -u):\$(id -g)\" --group-add \"\$socket_group\" --network host -e PATH=\"\$taskdir:/usr/local/bin:/usr/bin:/bin\" -v \"\$backup_root:\$backup_root\" -v /var/run/docker.sock:/var/run/docker.sock python:3.12-alpine python \"\$helper\" \"\$@\";
fi"
