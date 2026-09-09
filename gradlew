#!/bin/sh

##############################################################################
# Gradle start up script for POSIX
##############################################################################

# Add default JVM options here.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false

# Use java if JAVA_HOME is not set
if [ -z "$JAVACMD" ] ; then
  if [ ! -z "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
  else
    JAVACMD="java"
  fi
fi

if [ "$1" = "--version" ] ; then
  echo "Gradle wrapper — placeholder for CI build"
  exit 0
fi

exec "$JAVACMD" "$@"
