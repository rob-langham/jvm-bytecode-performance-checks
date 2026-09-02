#!/usr/bin/env bash
# Runs the JDK compatibility matrix locally in Docker, one container per leg.
#
#   scripts/jdk-docker-matrix.sh              # all legs: 8 11 17 21 25
#   scripts/jdk-docker-matrix.sh 25           # just one
#   GRADLE_TASKS=":core:test" scripts/jdk-docker-matrix.sh 17   # narrower suite legs
#
# Two kinds of leg, matching what each JDK can actually do:
#
#   17/21/25 - suite legs. The full build runs with the tests executing on that JVM. Gradle
#   itself always runs on 17 (Gradle 8.11 cannot run on Java 25, and which JVMs Gradle runs on
#   is Gradle's compatibility story, not this library's); jdk-matrix.init.gradle re-points the
#   test launcher, and the Foojay resolver downloads the matrix JDK inside the container.
#
#   8/11 - target legs, run ENTIRELY on the target JDK. The leg compiles an annotated probe
#   with that era's REAL javac (not a modern javac with --release, whose output can differ in
#   shape) against the core jar, then compiles and runs the checker over the result on that same
#   JVM and asserts the findings. These legs are the acceptance test for the tool running on
#   Java 8: they stay red until the runtime side is compiled for release 8.
#
# Legs run sequentially against the mounted worktree; suite legs use --rerun-tasks so a later
# leg cannot ride on an earlier leg's up-to-date outputs. Dependency and toolchain downloads
# persist in a named volume, so only the first ever run pays for them.
set -u

cd "$(dirname "$0")/.."

LEGS=("$@")
[ ${#LEGS[@]} -eq 0 ] && LEGS=(8 11 17 21 25)
TASKS=${GRADLE_TASKS:-build}
CACHE_VOLUME=perf-static-checks-gradle-cache
GRADLE_IMAGE=eclipse-temurin:17-jdk
CORE_JAR=core/build/libs/core-0.1.0.jar
AGENT_JAR=core/build/libs/core-0.1.0-agent.jar

command -v docker >/dev/null || { echo "docker is not installed or not on PATH" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "docker daemon is not running" >&2; exit 1; }

in_container() { # image, then the command
    local image=$1; shift
    docker run --rm -v "$PWD:/work" -v "$CACHE_VOLUME:/root/.gradle" -w /work "$image" "$@"
}

suite_leg() {
    in_container "$GRADLE_IMAGE" \
        ./gradlew $TASKS --no-daemon --rerun-tasks --stacktrace \
        -I scripts/jdk-matrix.init.gradle "-Dmatrix.test.jvm=$1"
}

jars_built=0
target_leg() {
    local jdk=$1
    if [ $jars_built -eq 0 ]; then
        echo "-- building the core and agent jars the target legs analyse with"
        in_container "$GRADLE_IMAGE" ./gradlew :core:jar :core:shadowJar --no-daemon -q || return 1
        jars_built=1
    fi
    local out="build/jdk-matrix/probe$jdk"
    rm -rf "$out" build/jdk-matrix/runner && mkdir -p "$out" build/jdk-matrix/runner
    echo "-- compiling probe and checker runner, then running the checker, all on JDK $jdk"
    # The agent jar carries the checker with ASM relocated inside, so it is the one artifact
    # that can run the checker with nothing else on the classpath. Everything happens on the
    # target JDK: compiling RunChecker against the jar is itself part of the test, since javac
    # rejects any referenced class file newer than its own release.
    in_container "eclipse-temurin:${jdk}-jdk" sh -c \
        "javac -cp $CORE_JAR -d $out scripts/jdk-matrix-probe/Probe.java \
         && javac -cp $AGENT_JAR -d build/jdk-matrix/runner scripts/jdk-matrix-probe/RunChecker.java \
         && java -cp $AGENT_JAR:build/jdk-matrix/runner RunChecker $out"
}

declare -a RESULTS
failed=0

for leg in "${LEGS[@]}"; do
    echo
    echo "============================================================"
    case $leg in
        8|11) echo " JDK $leg  ·  target leg: real javac $leg + checker on 17" ;;
        *)    echo " JDK $leg  ·  suite leg: gradle $TASKS, tests on a $leg JVM" ;;
    esac
    echo "============================================================"
    case $leg in
        8|11) target_leg "$leg" ;;
        *)    suite_leg "$leg" ;;
    esac
    if [ $? -eq 0 ]; then
        RESULTS+=("JDK $leg: PASS")
    else
        RESULTS+=("JDK $leg: FAIL")
        failed=1
    fi
done

echo
echo "================ JDK matrix ================"
printf '  %s\n' "${RESULTS[@]}"
echo "============================================"
exit $failed
