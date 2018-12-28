#!/bin/bash

set -euo pipefail

DRY_RUN="-DdryRun=true"

if [[ "$#" == "1" ]]; then
  if [[ "$1" != "-f" ]]; then
    echo "Only possible argument is '-f'. Please check your call"
    exit 1
  else
    DRY_RUN="-DdryRun=false"
  fi
else
  echo "Running permissions check"
fi

java $DRY_RUN \
     -DdefinitionsDir=$PWD/permissions \
     -DartifactoryApiTempDir=$PWD/json \
     -DartifactoryUserNamesJsonListUrl=https://reports.jenkins.io/artifactory-ldap-users-report.json \
     -jar target/repository-permissions-updater-*-bin/repository-permissions-updater-*.jar
