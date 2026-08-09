#!/bin/bash

set -euo pipefail

ENVIRONMENT="${ENVIRONMENT:-dev}"
AWS_REGION="${AWS_REGION:-ap-south-1}"

SSM_DB_PASSWORD_PARAMETER="/casiq/${ENVIRONMENT}/database/password"

echo "Loading DB credentials for environment: ${ENVIRONMENT}"

POSTGRES_PASSWORD=$(
    aws ssm get-parameter \
        --name "${SSM_DB_PASSWORD_PARAMETER}" \
        --with-decryption \
        --region "${AWS_REGION}" \
        --query "Parameter.Value" \
        --output text
)

if [ -z "${POSTGRES_PASSWORD}" ]; then
    echo "ERROR: Could not retrieve database password"
    exit 1
fi

export POSTGRES_PASSWORD

docker compose up --build
