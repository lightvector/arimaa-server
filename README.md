# arimaa-server
Arimaa server

## Getting started

1. Install [sbt](http://www.scala-sbt.org/download.html)
2. `$> sbt`
3. `container:start`
4. [Go to site](http://localhost:8080)

## API

* See https://github.com/lightvector/arimaa-server/wiki/Arimaa-Game-Server-JSON-API for a draft of the API being targeted for implementation.

## SBT memory configuration

Note that to avoid memory leaks via Java's permanent generation in a long-running sbt process,
you may need to edit your sbt configuration (i.e. the sbt script installed at ~/bin/sbt) if
you have Java 1.7 or earlier. If you do encounter out-of-memory issues in sbt, try adding the
following flags:

    -XX:+CMSClassUnloadingEnabled
    -XX:+UseConcMarkSweepGC
    -XX:MaxPermSize=1G"

## Contributors

* lightvector
* mattj256
* aaronyzhou

