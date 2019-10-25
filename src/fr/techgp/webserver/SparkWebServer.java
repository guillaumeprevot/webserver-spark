package fr.techgp.webserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BiFunction;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.time.FastDateFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;

public class SparkWebServer {

	private static final String logPath = System.getProperty("webserver.log", "webserver.log");
	private static final String confPath = System.getProperty("webserver.conf", "webserver.conf");
	private static final Logger logger = prepareLogger();

	private static final Logger prepareLogger() {
		if (!"none".equals(logPath))
			System.setProperty("org.slf4j.simpleLogger.logFile", logPath);
		System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
		System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "dd/MM/yyyy HH:mm:ss");
		System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
		System.setProperty("org.slf4j.simpleLogger.showLogName", "false");
		System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
		System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
		return LoggerFactory.getLogger(SparkWebServer.class);
	}

	public static final void main(String[] args) {
		try {
			// Log start
			info("Starting application...");

			// Load configuration
			Properties properties = new Properties();
			String[] pathParts = confPath.split(File.pathSeparator);
			for (String pathPart : pathParts) {
				File configFile = new File(pathPart);
				if (configFile.exists()) {
					try (FileInputStream fis = new FileInputStream(configFile)) {
						properties.load(fis);
					}
				}
			}

			// Get configuration
			BiFunction<String, String, String> settings = (name, defaultValue) -> {
				String s = System.getProperty(name);
				return (s != null) ? s : properties.getProperty(name, defaultValue);
			};

			// Static resources
			// Spark.externalStaticFileLocation("public");

			// Optional HTTPS
			String keystore = settings.apply("server.keystore", null);
			if (keystore != null)
				Spark.secure(keystore, settings.apply("server.keystore.password", null), null, null);

			// Port
			int port = Integer.parseInt(settings.apply("server.port", "10001"));
			Spark.port(port);

			// Routes
			int i = 0;
			String folder = settings.apply("static." + i + ".folder", null);
			String prefix = settings.apply("static." + i + ".prefix", "");
			while (folder != null) {
				Spark.get(prefix + "/*", new StaticRessourceWithCache(prefix, folder, settings));
				i++;
				folder = settings.apply("static." + i + ".folder", null);
				prefix = settings.apply("static." + i + ".prefix", "");
			}

			// Trace for requested URLs that do not exist in shared folders
			Spark.notFound((request, response) -> reply(response, request.pathInfo(), HttpServletResponse.SC_NOT_FOUND));

			// Log started
			// (linux only, all jvm) String pid = new File("/proc/self").getCanonicalFile().getName();
			String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
			try (FileOutputStream os = new FileOutputStream(new File("webserver.pid"))) {
				os.write(pid.getBytes());
			}
			info("Application started on " + (keystore != null ? "HTTPS" : "HTTP") + " port " + port + " with PID " + pid);
		} catch (Exception ex) {
			error("Application stopped because of unexpected errors.", ex);
		}
	}

	private static final class StaticRessourceWithCache implements Route {

		private static final FastDateFormat CACHE_DATE_FORMAT = FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

		private final String prefix;
		private final File folder;
		private final BiFunction<String, String, String> settings;

		public StaticRessourceWithCache(String prefix, String folder, BiFunction<String, String, String> settings) {
			this.prefix = prefix;
			this.folder = new File(folder);
			this.settings = settings;
		}

		@Override
		public Object handle(Request request, Response response) throws Exception {
			// Récupérer le chemin demandé
			String path = request.pathInfo();
			try {
				// Par sécurité, vérifier que l'utilisateur ne cherche pas autre chose
				if (path.contains(".."))
					return reply(response, path, HttpServletResponse.SC_FORBIDDEN);

				// Récupérer le fichier associé
				File file = new File(this.folder, path.substring(this.prefix.length()));

				// Vérifier que le fichier existe
				if (!file.exists())
					return reply(response, path, HttpServletResponse.SC_NOT_FOUND);

				// Commencer la réponse en précisant quelques en-têtes liées à la date de modificaiton
				Date fileDate = new Date(file.lastModified());
				String lastModified = CACHE_DATE_FORMAT.format(fileDate);
				String etag = sha1(lastModified);
				response.header("Cache-Control", "no-cache");
				response.header("Etag", etag);
				response.header("Last-Modified", lastModified);

				// Vérifier le 1er type de cache : If-None-Match :""9e3fa9259d22837a4e72fe8b69112968b88e3cca""
				String ifNoneMatch = request.headers("If-None-Match");
				if (ifNoneMatch == null || !ifNoneMatch.equals(etag)) {
					// Vérifier le 2ème type de cache : If-Modified-Since :"Mon, 16 Mar 2015 07:42:10 GMT"
					String ifModifiedSince = request.headers("If-Modified-Since");
					if (ifModifiedSince == null || !ifModifiedSince.equals(lastModified)) {
						// Tant pis, pas de cache
						response.header("Date", lastModified);
						// Indiquer le bon type MIME, si on le connait
						String extension = path.substring(path.lastIndexOf(".") + 1);
						String mimetype = this.settings.apply("mimetype." + extension, "application/octet-stream");
						response.type(mimetype);
						// Et renvoyer la ressource demandée
						try (InputStream is = new FileInputStream(file)) {
							copy(is, response.raw().getOutputStream(), new byte[1024 * 1024]);
							return reply(response, path, HttpServletResponse.SC_OK);
						}
					}
				}

				// OK, la donnée en cache semble à jour, on renvoie le statut 304 (Not Modified)
				response.header("Content-Length", "0");
				return reply(response, path, HttpServletResponse.SC_NOT_MODIFIED);

			} catch (Exception ex) {
				Spark.halt(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error"); // 500
				error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR + " : " + path, ex);
				return null;
			}
		}

	}

	/**
	 * Pour tracer un message informatif "text" (via "logger.info" et "System.out.println")
	 */
	public static final void info(String text) {
		if (logger.isInfoEnabled())
			logger.info(text);
		System.out.println(text);
	}

	/**
	 * Pour tracer une erreur "text" liée à une exception "ex" (via "logger.error", "System.err.println" et "ex.printStackTrace")
	 */
	public static final void error(String text, Exception ex) {
		if (logger.isErrorEnabled())
			logger.error(text, ex);
		System.err.println(text);
		ex.printStackTrace();
	}

	/**
	 * Pour tracer une réponse sans erreur, qui peut être SC_OK, SC_NOT_MODIFIED, SC_NOT_FOUND ou SC_FORBIDDEN
	 */
	public static final String reply(Response response, String path, int statusCode) {
		response.status(statusCode);
		if (statusCode < 400 && logger.isTraceEnabled())
			logger.trace(statusCode + " : " + path);
		else if (statusCode >= 400 && logger.isWarnEnabled())
			logger.warn(statusCode + " : " + path);
		System.out.println(statusCode + " : " + path);
		return "";
	}

	/**
	 * Calcule la checksum SHA-1 de la chaine donnée et renvoie la représentation en héxa du résultat
	 */
	public static final String sha1(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			digest.update(value.getBytes("utf8"));
			byte[] digestBytes = digest.digest();
			return Hex.encodeHexString(digestBytes);
		} catch (NoSuchAlgorithmException|UnsupportedEncodingException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Copie un flux dans un flux en utilisant le buffer fourni
	 */
	public static final long copy(InputStream is, OutputStream os, byte[] buffer) throws IOException {
		long count = 0;
		int n = 0;
		while (-1 != (n = is.read(buffer))) {
			os.write(buffer, 0, n);
			count += n;
		}
		return count;
	}

}
