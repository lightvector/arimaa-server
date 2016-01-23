# arimaa-server
This site is currently live in alpha at: [playArimaa.org](http://playarimaa.org/)

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

1. Set up an empty postgres database, (Google for how to do this), editing `src/main/resources/application.conf` with the values necessary to connect to the database.
2. Run the command `sbt -DisProd=true 'run-main org.playarimaa.server.CreateTables'` from the command line to create the necessary tables in the empty database.
3. Set up an email/smtp server, or register for an online service for smtp (such as Amazon SES if you're using Amazon Web Services), again editing `src/main/resources/application.conf` as needed so that the server can send email.
4. Run `gulp build` as necessary if you make any changes to the frontend in the process.
5. Within sbt, run `startServerProd` to start up the webserver in production mode.

To run the server separately, not within SBT, such as you might do on a production machine rather than one used for development:

6. Download an appropriate Java servlet engine such as Jetty: http://www.eclipse.org/jetty/. For a quick lightweight start, consider Jetty Runner: http://www.eclipse.org/jetty/documentation/9.2.3.v20140905/runner.html
7. Within sbt, run `clean` and then `package` to build a ".war" file in target/scala-2.11/. This is the packaged servlet that can then be run using Jetty or the servlet engine. Note that `clean` is often necessary here because if there are stale unused build files left over from development, they can sometimes actually get packaged in with the .war file  and cause issues.

## SBT memory configuration

Note that to avoid memory leaks via Java's permanent generation in a long-running sbt process,
you may need to edit your sbt configuration (i.e. the sbt script installed at ~/bin/sbt) if
you have Java 1.7 or earlier. If you do encounter out-of-memory issues in sbt, try editing the script
to call Java with the following flags:

    -XX:+CMSClassUnloadingEnabled
    -XX:+UseConcMarkSweepGC
    -XX:MaxPermSize=1G

## API

See https://github.com/lightvector/arimaa-server/wiki/Arimaa-Game-Server-JSON-API for the current API.

As the site is new and major features are still being worked out, the API is subject to change, although most of the basic queries, such as those directly involved in joining and playing games are unlikely to change much more at this point (except for possibly the addition of new fields to the return values of the queries).


## License

The contents of this repository are available under a BSD-style license. However, please note that this license alone does not grant usage for purposes related to the Arimaa game or any rights the Arimaa game itself - for that, please contact the creator of Arimaa at http://arimaa.com/.

See the included LICENSE.txt file for more details: https://github.com/lightvector/arimaa-server/blob/master/LICENSE.txt


## Contributors

* lightvector
* mattj256
* aaronyzhou

