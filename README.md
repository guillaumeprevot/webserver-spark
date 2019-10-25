# webserver-spark

Web server based on Spark (Java) to serve mostly static files.

## Presentation

This project is a one-class Web Server written in Java using [Spark](http://sparkjava.com/).

The default behaviour is :

- run on port `10001` in HTTP and share the `public` folder as root (and only) folder (try [this URL](http://localhost:10001/index.html) with default configuration)
- read configuration from `webserver.conf` (use `-Dwebserver.conf=another-file.conf` to change it's location)
- write traces in `webserver.log` (use `-Dwebserver.log=none` to disable logging or `-Dwebserver.log=another-file.log` to change it's location)
- write process id in `webserver.pid` when application is started to make termination with a script more convenient

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
