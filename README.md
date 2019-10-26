# webserver-spark

Web server based on Spark (Java) to serve mostly static files.

## Presentation

This project is a [one-class](./src/fr/techgp/webserver/SparkWebServer.java) Web Server written in Java using [Spark](http://sparkjava.com/).

The default behaviour is :

- run on port `10001` in HTTP and share the `public` folder as root (and only) folder
    - check access to http://localhost:10001/index.html with default configuration
- read configuration from `webserver.conf`
    - use `-Dwebserver.conf=another-file.conf` to change it's location
    - use `-Dwebserver.conf=default.conf:customized.conf` to use both files
- write traces in `webserver.log`
    - use `-Dwebserver.log=another-file.log` to change it's location
    - use `-Dwebserver.log=none` to disable logging
- write process id in `webserver.pid` when application is started
    - use `-Dwebserver.pid=another-file.pid` to change it's location
    - this should make termination easier, like ``kill -9 `cat webserver.pid` ``

The configuration should be easily customized. For instance, to share 2 folders with HTTPS enabled, you could use this configuration :

```properties
server.port=10001
server.keystore=webserver.pkcs12
server.keystore.password=CHANGEME
static.0.folder=public
static.0.prefix=/public
static.1.folder=/path/to/folder2
static.1.prefix=/public2
```

If you want to give it a try, go for it !

```bash
git clone https://github.com/guillaumeprevot/webserver-spark.git
cd webserver-spark
mvn compile
java -cp ./bin:./lib/* -Djdk.tls.ephemeralDHKeySize=2048 -Djdk.tls.rejectClientInitiatedRenegotiation=true fr.techgp.webserver.SparkWebServer
```

If you want to share any thoughts about it, feel free to do so !
