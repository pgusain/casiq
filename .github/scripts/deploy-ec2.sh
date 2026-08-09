#!/usr/bin/env sh

set -eu

# ---------------------------------------------------------------------------
# Required configuration
# ---------------------------------------------------------------------------

: "${IMAGE_REF:?IMAGE_REF is required}"
: "${GHCR_USER:?GHCR_USER is required}"
: "${GHCR_TOKEN:?GHCR_TOKEN is required}"
: "${APP_ENV_FILE:?APP_ENV_FILE is required}"
: "${ENVIRONMENT:?ENVIRONMENT is required}"

# ---------------------------------------------------------------------------
# Optional configuration
# ---------------------------------------------------------------------------

CONTAINER_NAME="${CONTAINER_NAME:-casiq-app}"

APP_PORT="${APP_PORT:-8080}"
APP_BIND_ADDRESS="${APP_BIND_ADDRESS:-127.0.0.1}"

ATTACHMENT_DATA_DIR="${ATTACHMENT_DATA_DIR:-/opt/casiq/data/attachments}"

AWS_REGION="${AWS_REGION:-us-east-1}"

COMPOSE_FILE="${COMPOSE_FILE:-compose.yaml}"

SSM_DB_PASSWORD_PARAMETER="${SSM_DB_PASSWORD_PARAMETER:-/casiq/${ENVIRONMENT}/database/password}"

# ---------------------------------------------------------------------------
# Validate required tools
# ---------------------------------------------------------------------------

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is not installed on the EC2 host." >&2
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose is not installed on the EC2 host." >&2
    exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
    echo "AWS CLI is not installed on the EC2 host." >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is not installed on the EC2 host." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Validate files
# ---------------------------------------------------------------------------

if [ ! -r "$APP_ENV_FILE" ]; then
    echo "Application environment file is not readable: $APP_ENV_FILE" >&2
    exit 1
fi

if [ ! -r "$COMPOSE_FILE" ]; then
    echo "Docker Compose file is not readable: $COMPOSE_FILE" >&2
    exit 1
fi

mkdir -p "$ATTACHMENT_DATA_DIR"

# ---------------------------------------------------------------------------
# Determine currently deployed image for rollback
# ---------------------------------------------------------------------------

previous_image="$(
    docker inspect \
        --format '{{.Config.Image}}' \
        "$CONTAINER_NAME" \
        2>/dev/null || true
)"

# ---------------------------------------------------------------------------
# Retrieve DB password from AWS SSM Parameter Store
# ---------------------------------------------------------------------------

echo "Loading database credentials for environment: $ENVIRONMENT"
echo "SSM parameter: $SSM_DB_PASSWORD_PARAMETER"

POSTGRES_PASSWORD="$(
    aws ssm get-parameter \
        --name "$SSM_DB_PASSWORD_PARAMETER" \
        --with-decryption \
        --region "$AWS_REGION" \
        --query 'Parameter.Value' \
        --output text
)"

if [ -z "$POSTGRES_PASSWORD" ] || [ "$POSTGRES_PASSWORD" = "None" ]; then
    echo "Unable to retrieve database password from AWS SSM." >&2
    exit 1
fi

# Export values used by compose.yaml variable interpolation.
export POSTGRES_PASSWORD
export IMAGE_REF
export APP_PORT
export APP_BIND_ADDRESS
export ATTACHMENT_DATA_DIR

# ---------------------------------------------------------------------------
# Login to GitHub Container Registry
# ---------------------------------------------------------------------------

printf '%s' "$GHCR_TOKEN" |
    docker login ghcr.io \
        --username "$GHCR_USER" \
        --password-stdin

# ---------------------------------------------------------------------------
# Pull application image
# ---------------------------------------------------------------------------

if ! docker pull "$IMAGE_REF"; then
    docker logout ghcr.io >/dev/null 2>&1 || true
    unset POSTGRES_PASSWORD
    exit 1
fi

docker logout ghcr.io >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Docker Compose helpers
# ---------------------------------------------------------------------------

compose() {
    docker compose \
        --file "$COMPOSE_FILE" \
        --env-file "$APP_ENV_FILE" \
        "$@"
}

start_stack() {
    compose up \
        --detach \
        --remove-orphans
}

# ---------------------------------------------------------------------------
# Application health check
# ---------------------------------------------------------------------------

wait_until_healthy() {
    attempts=0

    while [ "$attempts" -lt 30 ]; do
        if curl \
            --fail \
            --silent \
            --show-error \
            "http://127.0.0.1:${APP_PORT}/" \
            >/dev/null 2>&1; then
            return 0
        fi

        attempts=$((attempts + 1))
        sleep 2
    done

    return 1
}

# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------

echo "Deploying $IMAGE_REF..."

if ! start_stack; then
    echo "Docker Compose failed to start the application stack." >&2
    unset POSTGRES_PASSWORD
    exit 1
fi

# ---------------------------------------------------------------------------
# Verify deployment
# ---------------------------------------------------------------------------

if ! wait_until_healthy; then
    echo "Deployment health check failed for $IMAGE_REF." >&2

    compose logs --tail 200 casiq || true

    # -----------------------------------------------------------------------
    # Rollback
    # -----------------------------------------------------------------------

    if [ -n "$previous_image" ] && [ "$previous_image" != "$IMAGE_REF" ]; then
        echo "Rolling back to $previous_image." >&2

        IMAGE_REF="$previous_image"
        export IMAGE_REF

        compose up \
            --detach \
            --remove-orphans \
            casiq

        if wait_until_healthy; then
            echo "Rollback to $previous_image succeeded." >&2
        else
            echo "Rollback health check also failed." >&2
            compose logs --tail 200 casiq || true
        fi
    else
        echo "No previous application image is available for rollback." >&2
    fi

    unset POSTGRES_PASSWORD
    exit 1
fi

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

docker image prune --force >/dev/null

unset POSTGRES_PASSWORD

echo "Deployed $IMAGE_REF as $CONTAINER_NAME on port $APP_PORT."
echo "Environment: $ENVIRONMENT"
