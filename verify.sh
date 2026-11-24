#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "$0")"
if (( $# > 1 )) || { (( $# == 1 )) && [[ ! $1 =~ ^[A-Za-z][A-Za-z0-9_]*(,[A-Za-z][A-Za-z0-9_]*)*$ ]]; }; then
  printf 'Usage: %s [comma-separated integration test classes]\n' "$0" >&2
  exit 2
fi

git diff --check HEAD
node docs/api/check-contracts.mjs

maven=mvn
if ! command -v "$maven" >/dev/null && command -v mvn.cmd >/dev/null; then
  maven=mvn.cmd
fi

tests=OpenApiContractParityIT,Tier3Be2ContractWiringIT,SchemaMigrationIT
if (( $# == 1 )); then
  tests="$tests,$1"
fi

# The verifier runs Surefire, compiles Java with the project compiler, packages
# the API, and runs the selected Failsafe tests against PostgreSQL.
mkdir -p BE/target
stamp=$(mktemp BE/target/verify.XXXXXX)
trap 'rm -f -- "$stamp"' EXIT
"$maven" -B -f BE/pom.xml "-Dit.test=$tests" verify

# Failsafe succeeds if any one requested pattern matches. Require a fresh,
# nonempty report for each explicitly named class.
IFS=, read -r -a classes <<< "$tests"
for test_class in "${classes[@]}"; do
  ran=false
  for report in BE/target/failsafe-reports/TEST-*."$test_class".xml; do
    [[ "$report" -nt "$stamp" ]] || continue
    suite=$(grep -m 1 '<testsuite ' "$report")
    [[ "$suite" =~ tests=\"([0-9]+)\" ]] || continue
    count="${BASH_REMATCH[1]}"
    [[ "$suite" =~ skipped=\"([0-9]+)\" ]] || continue
    if (( count > BASH_REMATCH[1] )); then
      ran=true
      break
    fi
  done
  if [[ "$ran" != true ]]; then
    printf 'Missing fresh test report for %s\n' "$test_class" >&2
    exit 1
  fi
done
