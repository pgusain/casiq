#!/usr/bin/env sh

set -eu

# ---------------------------------------------------------------------------
# Required deployment configuration
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
COMPOSE_FILE="${COMPOSE_FILE:-/opt/casiq/compose.yaml}"
SSM_DB_PASSWORD_PARAMETER="${SSM_DB_PASSWORD_PARAMETER:-/casiq/${ENVIRONMENT}/database/password}"
NGINX_ENABLED="${NGINX_ENABLED:-true}"
NGINX_LISTEN_PORT="${NGINX_LISTEN_PORT:-80}"
NGINX_DOMAIN="${NGINX_DOMAIN:-demo.example.co.in}"
NGINX_REPO_CONFIG="${NGINX_REPO_CONFIG:-ngnix.conf}"
NGINX_CONFIG_URL="${NGINX_CONFIG_URL:-https://raw.githubusercontent.com/pgusain/casiq/main/ngnix.conf}"

# The runbook downloads Compose from the latest release URL. For production,
# set DOCKER_COMPOSE_VERSION to a tested version such as v2.x.y.
DOCKER_COMPOSE_VERSION="${DOCKER_COMPOSE_VERSION:-latest}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

log() {
    printf '%s\n' "[deploy] $*"
}

warn() {
    printf '%s\n' "[deploy] WARNING: $*" >&2
}

fail() {
    printf '%s\n' "[deploy] ERROR: $*" >&2
    exit 1
}

if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
elif command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
else
    fail "Root privileges are required for host prerequisite installation, but sudo is unavailable."
fi

as_root() {
    if [ -n "$SUDO" ]; then
        sudo "$@"
    else
        "$@"
    fi
}

PACKAGE_FAMILY=""
if command -v dnf >/dev/null 2>&1; then
    PACKAGE_FAMILY="dnf"
elif command -v yum >/dev/null 2>&1; then
    PACKAGE_FAMILY="yum"
elif command -v apt-get >/dev/null 2>&1; then
    PACKAGE_FAMILY="apt"
else
    fail "Unsupported EC2 operating system. Expected dnf, yum, or apt-get."
fi

install_packages() {
    case "$PACKAGE_FAMILY" in
        dnf)
            as_root dnf install -y "$@"
            ;;
        yum)
            as_root yum install -y "$@"
            ;;
        apt)
            as_root env DEBIAN_FRONTEND=noninteractive apt-get update -y
            as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
            ;;
    esac
}

ensure_curl() {
    if command -v curl >/dev/null 2>&1; then
        log "curl is already installed: $(curl --version | head -n 1)"
        return
    fi

    log "curl is missing; installing it."
    install_packages curl
    command -v curl >/dev/null 2>&1 || fail "curl installation failed."
}

ensure_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        log "Docker is missing; installing it."
        case "$PACKAGE_FAMILY" in
            dnf|yum)
                install_packages docker
                ;;
            apt)
                install_packages docker.io
                ;;
        esac
    else
        log "Docker is already installed: $(docker --version 2>/dev/null || true)"
    fi

    command -v docker >/dev/null 2>&1 || fail "Docker installation failed."

    if command -v systemctl >/dev/null 2>&1; then
        as_root systemctl enable docker
        as_root systemctl start docker
    fi

    # Keep the host prepared for future interactive sessions. Group membership
    # does not refresh in the current SSH process, so docker_cmd() below can
    # fall back to sudo for this deployment if necessary.
    DEPLOY_USER="${SUDO_USER:-${USER:-}}"
    if [ -n "$DEPLOY_USER" ] && [ "$DEPLOY_USER" != "root" ]; then
        if ! id -nG "$DEPLOY_USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
            log "Adding $DEPLOY_USER to the docker group."
            as_root usermod -aG docker "$DEPLOY_USER"
            warn "$DEPLOY_USER was added to the docker group; the new group applies on the next login."
        fi
    fi
}

docker_cmd() {
    if docker info >/dev/null 2>&1; then
        docker "$@"
    elif [ -n "$SUDO" ] && sudo docker info >/dev/null 2>&1; then
        sudo docker "$@"
    else
        fail "Docker daemon is unavailable or the deployment user cannot access it."
    fi
}

ensure_docker_compose() {
    if docker_cmd compose version >/dev/null 2>&1; then
        log "Docker Compose is already installed: $(docker_cmd compose version)"
        return
    fi

    log "Docker Compose v2 plugin is missing; installing it."

    ARCH="$(uname -m)"
    case "$ARCH" in
        x86_64)
            COMPOSE_ARCH="x86_64"
            ;;
        aarch64|arm64)
            COMPOSE_ARCH="aarch64"
            ;;
        *)
            fail "Unsupported architecture for Docker Compose: $ARCH"
            ;;
    esac

    as_root mkdir -p /usr/local/lib/docker/cli-plugins

    if [ "$DOCKER_COMPOSE_VERSION" = "latest" ]; then
        COMPOSE_URL="https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${COMPOSE_ARCH}"
    else
        COMPOSE_URL="https://github.com/docker/compose/releases/download/${DOCKER_COMPOSE_VERSION}/docker-compose-linux-${COMPOSE_ARCH}"
    fi

    as_root curl -fSL "$COMPOSE_URL" -o /usr/local/lib/docker/cli-plugins/docker-compose
    as_root chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

    docker_cmd compose version >/dev/null 2>&1 || fail "Docker Compose installation failed."
    log "Installed Docker Compose: $(docker_cmd compose version)"
}

ensure_aws_cli() {
    if command -v aws >/dev/null 2>&1; then
        log "AWS CLI is already installed: $(aws --version 2>&1)"
        return
    fi

    log "AWS CLI is missing; installing it."
    case "$PACKAGE_FAMILY" in
        dnf)
            if ! as_root dnf install -y awscli2; then
                as_root dnf install -y awscli
            fi
            ;;
        yum)
            if ! as_root yum install -y awscli2; then
                as_root yum install -y awscli
            fi
            ;;
        apt)
            install_packages awscli
            ;;
    esac

    command -v aws >/dev/null 2>&1 || fail "AWS CLI installation failed."
    log "Installed AWS CLI: $(aws --version 2>&1)"
}

ensure_directories() {
    log "Ensuring CASIQ application directories exist."
    as_root mkdir -p /opt/casiq/config
    as_root mkdir -p "$ATTACHMENT_DATA_DIR"

    DEPLOY_USER="${SUDO_USER:-${USER:-}}"
    if [ -n "$DEPLOY_USER" ] && [ "$DEPLOY_USER" != "root" ]; then
        as_root chown -R "$DEPLOY_USER":"$(id -gn "$DEPLOY_USER")" /opt/casiq
    fi
}

ensure_nginx() {
    if [ "$NGINX_ENABLED" != "true" ]; then
        log "NGINX_ENABLED=$NGINX_ENABLED; skipping nginx setup."
        return
    fi

    if ! command -v nginx >/dev/null 2>&1; then
        log "nginx is missing; installing it."
        install_packages nginx
    else
        log "nginx is already installed: $(nginx -v 2>&1)"
    fi

    command -v nginx >/dev/null 2>&1 || fail "nginx installation failed."

    # The source of truth is ngnix.conf from the repository root. When the
    # deployment action copies that file to the EC2 working directory we use
    # it directly. Otherwise retrieve the same file from GitHub.
    NGINX_SOURCE=""
    NGINX_TMP=""
    if [ -r "$NGINX_REPO_CONFIG" ]; then
        NGINX_SOURCE="$NGINX_REPO_CONFIG"
        log "Using repository nginx configuration: $NGINX_SOURCE"
    else
        NGINX_TMP="/tmp/casiq-ngnix.conf.$$"
        log "$NGINX_REPO_CONFIG is not present on the host; downloading $NGINX_CONFIG_URL"
        curl -fSL "$NGINX_CONFIG_URL" -o "$NGINX_TMP" || \
            fail "Unable to retrieve nginx configuration from $NGINX_CONFIG_URL"
        NGINX_SOURCE="$NGINX_TMP"
    fi

    # Install the repository configuration as a complete nginx server config.
    # Use conf.d on both Amazon Linux/RHEL and Ubuntu/Debian; nginx packages on
    # these platforms include /etc/nginx/conf.d/*.conf from nginx.conf.
    as_root mkdir -p /etc/nginx/conf.d
    NGINX_TARGET="/etc/nginx/conf.d/casiq.conf"

    if as_root test -r "$NGINX_TARGET" && as_root cmp -s "$NGINX_SOURCE" "$NGINX_TARGET"; then
        log "nginx configuration is already current: $NGINX_TARGET"
    else
        log "Installing nginx configuration from $NGINX_SOURCE to $NGINX_TARGET"
        as_root cp "$NGINX_SOURCE" "$NGINX_TARGET"
    fi

    [ -z "$NGINX_TMP" ] || rm -f "$NGINX_TMP"

    # Remove package defaults that could compete for ports 80/443.
    if [ "$PACKAGE_FAMILY" = "apt" ]; then
        as_root rm -f /etc/nginx/sites-enabled/default
    fi

    as_root nginx -t || fail "nginx configuration validation failed. Check $NGINX_TARGET."

    if command -v systemctl >/dev/null 2>&1; then
        as_root systemctl enable nginx
        if as_root systemctl is-active --quiet nginx; then
            as_root systemctl reload nginx
        else
            as_root systemctl start nginx
        fi
        as_root systemctl is-active --quiet nginx || fail "nginx failed to start."
    fi

    log "nginx setup verified for $NGINX_DOMAIN using $NGINX_TARGET."
}

stop_all_docker_containers() {
    log "Stopping all currently running Docker containers before deployment."
    RUNNING_CONTAINERS="$(docker_cmd ps -q)"
    if [ -z "$RUNNING_CONTAINERS" ]; then
        log "No running Docker containers found."
        return
    fi

    # shellcheck disable=SC2086
    docker_cmd stop $RUNNING_CONTAINERS

    if [ -n "$(docker_cmd ps -q)" ]; then
        fail "One or more Docker containers are still running after stop."
    fi
    log "All running Docker containers have been stopped."
}

# ---------------------------------------------------------------------------
# EC2 host bootstrap / prerequisite verification
# ---------------------------------------------------------------------------

log "Checking EC2 host prerequisites."
if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    log "Host OS: ${PRETTY_NAME:-${NAME:-unknown}}"
fi

ensure_curl
ensure_docker
ensure_docker_compose

# Capture the currently deployed application image before stopping containers,
# so rollback can still restore it if the new deployment fails.
previous_image="$(
    docker_cmd inspect \
        --format '{{.Config.Image}}' \
        "$CONTAINER_NAME" \
        2>/dev/null || true
)"

stop_all_docker_containers
ensure_aws_cli
ensure_directories
ensure_nginx

# ---------------------------------------------------------------------------
# Validate deployment files
# ---------------------------------------------------------------------------

if [ ! -r "$APP_ENV_FILE" ]; then
    fail "Application environment file is not readable: $APP_ENV_FILE"
fi

if [ ! -r "$COMPOSE_FILE" ]; then
    fail "Docker Compose file is not readable: $COMPOSE_FILE"
fi

# ---------------------------------------------------------------------------
# Retrieve DB password from AWS SSM Parameter Store
# ---------------------------------------------------------------------------

log "Loading database credentials for environment: $ENVIRONMENT"
log "SSM parameter: $SSM_DB_PASSWORD_PARAMETER"

POSTGRES_PASSWORD="$(
    aws ssm get-parameter \
        --name "$SSM_DB_PASSWORD_PARAMETER" \
        --with-decryption \
        --region "$AWS_REGION" \
        --query 'Parameter.Value' \
        --output text
)"

if [ -z "$POSTGRES_PASSWORD" ] || [ "$POSTGRES_PASSWORD" = "None" ]; then
    fail "Unable to retrieve database password from AWS SSM. Verify the EC2 IAM role and parameter path."
fi

export POSTGRES_PASSWORD
export IMAGE_REF
export APP_PORT
export APP_BIND_ADDRESS
export ATTACHMENT_DATA_DIR

# ---------------------------------------------------------------------------
# Login to GitHub Container Registry and pull the immutable image
# ---------------------------------------------------------------------------

printf '%s' "$GHCR_TOKEN" |
    docker_cmd login ghcr.io \
        --username "$GHCR_USER" \
        --password-stdin

if ! docker_cmd pull "$IMAGE_REF"; then
    docker_cmd logout ghcr.io >/dev/null 2>&1 || true
    unset POSTGRES_PASSWORD
    fail "Unable to pull application image: $IMAGE_REF"
fi

docker_cmd logout ghcr.io >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Docker Compose helpers
# ---------------------------------------------------------------------------

compose() {
    docker_cmd compose \
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

log "Deploying $IMAGE_REF..."

if ! start_stack; then
    unset POSTGRES_PASSWORD
    fail "Docker Compose failed to start the application stack."
fi

# ---------------------------------------------------------------------------
# Verify deployment and rollback on failure
# ---------------------------------------------------------------------------

if ! wait_until_healthy; then
    warn "Deployment health check failed for $IMAGE_REF."
    compose logs --tail 200 casiq || true

    if [ -n "$previous_image" ] && [ "$previous_image" != "$IMAGE_REF" ]; then
        warn "Rolling back to $previous_image."

        IMAGE_REF="$previous_image"
        export IMAGE_REF

        compose up \
            --detach \
            --remove-orphans \
            casiq

        if wait_until_healthy; then
            warn "Rollback to $previous_image succeeded."
        else
            warn "Rollback health check also failed."
            compose logs --tail 200 casiq || true
        fi
    else
        warn "No previous application image is available for rollback."
    fi

    unset POSTGRES_PASSWORD
    exit 1
fi

# Validate nginx only after the application is healthy.
if [ "$NGINX_ENABLED" = "true" ]; then
    as_root nginx -t || fail "nginx configuration became invalid after deployment."
    if command -v systemctl >/dev/null 2>&1; then
        as_root systemctl is-active --quiet nginx || fail "nginx is not running."
    fi

    # HTTPS is the expected public endpoint. This check succeeds only after DNS,
    # security-group rules and the certificate referenced by ngnix.conf are valid.
    if curl --fail --silent --show-error --connect-timeout 10 "https://${NGINX_DOMAIN}/" >/dev/null 2>&1; then
        log "HTTPS nginx health check succeeded: https://${NGINX_DOMAIN}/"
    else
        warn "Application is healthy, but HTTPS validation failed for https://${NGINX_DOMAIN}/."
        warn "Verify DNS, EC2 security-group ports 80/443, and TLS certificate paths in ngnix.conf."
        if command -v systemctl >/dev/null 2>&1; then
            as_root systemctl status nginx --no-pager || true
        fi
        unset POSTGRES_PASSWORD
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

docker_cmd image prune --force >/dev/null
unset POSTGRES_PASSWORD

log "Deployed $IMAGE_REF as $CONTAINER_NAME on application port $APP_PORT."
log "Environment: $ENVIRONMENT"
if [ "$NGINX_ENABLED" = "true" ]; then
    log "Public endpoint: https://${NGINX_DOMAIN} -> nginx -> 127.0.0.1:$APP_PORT"
fi
