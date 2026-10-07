#!/bin/bash
# Helper to run Gradle with JDK 21 (avoid the bash tool risk shell)
export JAVA_HOME=/home/rubentxu/.local/share/mise/installs/java/temurin-21.0.8+9.0.LTS
export PATH="$JAVA_HOME/bin:$PATH"
exec ./gradlew "$@"