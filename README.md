# arimaa-server
[playArimaa](http://playarimaa.org)

## Getting started

### Setup
1. Install [sbt](http://www.scala-sbt.org/download.html) and [nodejs](https://nodejs.org/).
2. Clone this github repo to any desired directory and navigate to that directory.
3. Run `npm install` to install the necessary nodejs packages.
4. Install gulp if gulp is not installed, by `npm install -g gulp`.
5. Copy `src/main/resources/application.conf.example` to `src/main/resources/application.conf`, and edit the copy as desired to configure basic parameters for the server.
6. Run `gulp build` to compile the javascript and other frontend files.
7. Run `sbt`.
8. Within sbt, run `compile` to build the Scala backend and optionally `test` to run some local tests for the scala backend.
9. Within sbt, run `startServerTest` to start up the webserver using an in-memory database.
10. [Go to site](http://localhost:8080) and use the site!

### Further Setup
If you want to run the server in a production mode with persistent state (as opposed to an in-memory db that is erased when the server is restarted) and also have the email features of the site working properly:
1. Set up an empty postgres database and/or an email server, editing `src/main/resources/application.conf` appropriately.
2. Run the command `sbt -DisProd=true 'run-main org.playarimaa.server.CreateTables'` from the command line to create the necessary tables in the empty database.
3. Set up an smtp server or register for an online service (such as via gmail) so that the server can send email, editing `src/main/resources/application.conf` appropriately.
4. Run `gulp build` as necessary for any other changes you might have made to the frontend.
5. Within sbt, run `startServerProd` to start up the webserver in production mode.

## API

* See https://github.com/lightvector/arimaa-server/wiki/Arimaa-Game-Server-JSON-API for the current API.

## SBT memory configuration

Note that to avoid memory leaks via Java's permanent generation in a long-running sbt process,
you may need to edit your sbt configuration (i.e. the sbt script installed at ~/bin/sbt) if
you have Java 1.7 or earlier. If you do encounter out-of-memory issues in sbt, try editing the script
to call Java with the following flags:

    -XX:+CMSClassUnloadingEnabled
    -XX:+UseConcMarkSweepGC
    -XX:MaxPermSize=1G

## Contributors

* lightvector
* mattj256
* aaronyzhou

