#!/usr/bin/env bash
# Files a build failure as an issue.
#
# Action logs live on storage that is painful to read from anywhere but a
# browser, and this repo is worked on from a phone. An issue is readable
# everywhere, so that is where failures go.
set -u

short="${GITHUB_SHA:0:7}"
body=$(mktemp)

{
  echo "Commit \`${short}\` failed to build."
  echo
  echo '```'
  # Task lines are noise and there are hundreds of them, so they get dropped
  # before anything else. Whatever is left is the actual reason.
  grep -vE '^> Task ' build.log 2>/dev/null \
    | grep -E '^e: |^w: |FAILURE|What went wrong|Caused by|Execution failed|Could not|error:' \
    | head -40
  echo '```'
} > "$body"

if compgen -G "core/build/test-results/test/*.xml" > /dev/null; then
  {
    echo
    echo "Failing tests:"
    echo '```'
    python3 ci/test-failures.py core/build/test-results/test/*.xml | head -60
    echo '```'
  } >> "$body"
fi

gh issue create --title "build failed on ${short}" --body-file "$body"
