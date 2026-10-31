#!/bin/sh
# Runs a command inside the rehearsal bundle with Linux bind-mount semantics.
#
# The bundle lives in a Docker volume, i.e. inside Docker Desktop's Linux VM, and is mounted into this
# container at the very path the Docker daemon sees. Compose's relative bind mounts (./Caddyfile, ...) then
# resolve inside the VM and behave like on the droplet: a file replaced by a new inode (rsync, sed -i) is NOT
# seen by a running container. On macOS paths, Docker Desktop follows the path instead and hides that bug.
#
# Usage: linux-run.sh <command...>     e.g. linux-run.sh ./deploy.sh good
# Env:   REHEARSAL_VOLUME (default rehearsal-bundle), REHEARSAL_IMAGE (default rehearsal-cli),
#        DEPLOY_TIMEOUT_SECONDS (passed through; default 300)
VOLUME=${REHEARSAL_VOLUME:-rehearsal-bundle}
IMAGE=${REHEARSAL_IMAGE:-rehearsal-cli}
DIR=/var/lib/docker/volumes/$VOLUME/_data
exec docker run --rm -i --network host \
	-v /var/run/docker.sock:/var/run/docker.sock -v "$VOLUME:$DIR" -w "$DIR" \
	-e "CURL_HOME=$DIR/curlhome" -e "DEPLOY_TIMEOUT_SECONDS=${DEPLOY_TIMEOUT_SECONDS:-300}" \
	"$IMAGE" "$@"
