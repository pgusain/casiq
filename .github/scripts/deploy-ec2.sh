#!/usr/bin/env sh
set -eu

: "${IMAGE_REF:?IMAGE_REF is required}"
: "${GHCR_USER:?GHCR_USER is required}"
: "${GHCR_TOKEN:?GHCR_TOKEN is required}"
: "${APP_ENV_FILE:?APP_ENV_FILE is required}"

CONTAINER_NAME="${CONTAINER_NAME:-casiq}"
APP_PORT="${APP_PORT:-8080}"
APP_BIND_ADDRESS="${APP_BIND_ADDRESS:-127.0.0.1}"
ATTACHMENT_DATA_DIR="${ATTACHMENT_DATA_DIR:-/opt/casiq/data/attachments}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed on the EC2 host." >&2
  exit 1
fi

if [ ! -r "$APP_ENV_FILE" ]; then
  echo "Application environment file is not readable: $APP_ENV_FILE" >&2
  exit 1
fi

mkdir -p "$ATTACHMENT_DATA_DIR"
previous_image="$(docker inspect --format '{{.Config.Image}}' "$CONTAINER_NAME" 2>/dev/null || true)"

printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username "$GHCR_USER" --password-stdin
if ! docker pull "$IMAGE_REF"; then
  docker logout ghcr.io >/dev/null 2>&1 || true
  exit 1
fi
docker logout ghcr.io >/dev/null 2>&1 || true
docker rm --force "$CONTAINER_NAME" >/dev/null 2>&1 || true

start_container() {
  image="$1"
  docker run --detach \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    --env-file "$APP_ENV_FILE" \
    --publish "${APP_BIND_ADDRESS}:${APP_PORT}:8080" \
    --volume "${ATTACHMENT_DATA_DIR}:/work/data/attachments" \
    "$image"
}

wait_until_healthy() {
  attempts=0
  while [ "$attempts" -lt 30 ]; do
    if curl --fail --silent --show-error "http://127.0.0.1:${APP_PORT}/" >/dev/null; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  return 1
}

start_container "$IMAGE_REF"
if ! wait_until_healthy; then
  echo "Deployment health check failed for $IMAGE_REF." >&2
  docker logs --tail 200 "$CONTAINER_NAME" || true
  docker rm --force "$CONTAINER_NAME" >/dev/null 2>&1 || true

  if [ -n "$previous_image" ]; then
    echo "Rolling back to $previous_image." >&2
    start_container "$previous_image"
  fi
  exit 1
fi

docker image prune --force >/dev/null
echo "Deployed $IMAGE_REF as $CONTAINER_NAME on port $APP_PORT."
