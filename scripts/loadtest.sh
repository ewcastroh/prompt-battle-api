#!/usr/bin/env bash
# Load test (SPEC §14.3, PLAN I2): 2 cities x 50 virtual players x 3 attempts in 120 s against a RUNNING API.
# Wraps scripts/LoadTest.java (single-file Java 25 launch, JDK HttpClient, no dependencies).
#
#   PB_ADMIN_TOKEN_MDE=... PB_ADMIN_TOKEN_BOG=... scripts/loadtest.sh --reset            # fake profile, local
#   PB_ADMIN_TOKEN_MDE=... PB_ADMIN_TOKEN_BOG=... scripts/loadtest.sh --real --base-url https://<api>  # <= 40 images
#
# Exit code 0 = every gate passed. Options: scripts/loadtest.sh --help
set -euo pipefail
cd "$(dirname "$0")/.."
exec java scripts/LoadTest.java "$@"
