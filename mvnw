#!/bin/sh
# Thin wrapper pointing at the locally cached Maven 3.9.9 distribution.
MVN_HOME="$HOME/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9"
exec "$MVN_HOME/bin/mvn" "$@"