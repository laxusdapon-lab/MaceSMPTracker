#!/bin/sh
# Compile-checks the plugin against hand-written API STUBS and runs the plain-Java core self test.
# This is NOT the real Paper API: it only proves the code is internally consistent.
# Needs a JDK (javac). Run from the project root:  sh verification/check.sh
set -e
rm -rf /tmp/mace-check && mkdir -p /tmp/mace-check/stubbed /tmp/mace-check/core
javac -Xlint:all -d /tmp/mace-check/stubbed $(find verification/api-stubs -name '*.java') $(find src/main/java -name '*.java')
echo "plugin compiles against the stubs"
javac -d /tmp/mace-check/core \
  src/main/java/dev/macesmp/tracker/MaceRecord.java src/main/java/dev/macesmp/tracker/MaceRegistry.java \
  src/main/java/dev/macesmp/tracker/MaceNbt.java src/main/java/dev/macesmp/tracker/RegionScanner.java \
  src/main/java/dev/macesmp/tracker/DiskAudit.java src/test/java/dev/macesmp/tracker/CoreSelfTest.java
java -cp /tmp/mace-check/core dev.macesmp.tracker.CoreSelfTest
