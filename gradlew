#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"

# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`/"$link"
    fi
done

SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use the GRADLE_JVM_OPTS environment variable.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

# Use JNA if available
if [ "`uname -s`" = "Darwin" ] ; then
    # On macOS, use JNA by default
    if [ "`uname -m`" = "aarch64" ] ; then
        # On Apple Silicon, force use of aarch64 JNA library
        DEFAULT_JVM_OPTS="$DEFAULT_JVM_OPTS -Djna.library.path=/opt/homebrew/lib"
    fi
fi

# Exit the shell script with a status of 1
die() {
    echo
    echo "$*"
    echo
    exit 1
}

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
CLASSPATH=$(find "$APP_HOME/gradle/wrapper" -name "gradle-wrapper.jar" | head -n 1)

if [ -z "$CLASSPATH" ] || [ ! -f "$CLASSPATH" ]; then
    # try to download it if curl is available
    if command -v curl >/dev/null 2>&1;
    then
        echo "Downloading gradle-wrapper.jar..."
        mkdir -p "$APP_HOME/gradle/wrapper"
        curl -L -o "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
        CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
    fi
fi

if [ -z "$CLASSPATH" ] || [ ! -f "$CLASSPATH" ]; then
     die "ERROR: Gradle wrapper jar not found in $APP_HOME/gradle/wrapper/     
Please download it manually or ensure you have internet access for auto-download."
fi

# Pass all arguments to Gradle
exec "$JAVACMD" $DEFAULT_JVM_OPTS $GRADLE_JVM_OPTS "-Dorg.gradle.appname=$APP_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"