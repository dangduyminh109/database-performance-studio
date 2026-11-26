#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .env ]]; then
  echo '.env already exists.'
  exit 0
fi
umask 077
{
  printf 'STUDIO_METADATA_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'STUDIO_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
  printf 'STUDIO_SOURCE_HOSTS=localhost,127.0.0.1\n'
} > .env
echo 'Created .env.'
