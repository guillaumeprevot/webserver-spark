package fr.techgp.webserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.time.FastDateFormat;
import org.eclipse.jetty.io.EofException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;
import spark.utils.StringUtils;

public class SparkWebServer {

	private static final String pidPath = System.getProperty("webserver.pid", "webserver.pid");
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
			if (logger.isInfoEnabled())
				logger.info("Starting application...");

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

			// Some predefined features
			if ("true".equals(settings.apply("utils.ping.enabled", null)))
				Spark.get("/utils/ping", (req, resp) -> "pong");
			if ("true".equals(settings.apply("utils.ip.enabled", null)))
				Spark.get("/utils/ip", (req, resp) -> ip(req));
			if ("true".equals(settings.apply("utils.mimetype.enabled", null)))
				Spark.get("/utils/mimetype/:extension", (req, resp) -> mimetype(req, resp, settings));
			if ("true".equals(settings.apply("utils.moneyrates.enabled", null)))
				Spark.get("/utils/moneyrates", new MoneyRates(settings));
			if ("true".equals(settings.apply("utils.iblocklist.enabled", null)))
				Spark.get("/utils/iblocklist", new IBlockList(settings));


			// Trace for requested URLs that do not exist in shared folders
			Spark.get("/*", (request, response) -> reply(response, request.pathInfo(), HttpServletResponse.SC_NOT_FOUND));

			// Prepare "pid" file
			// String pid = new File("/proc/self").getCanonicalFile().getName(); // (linux only, all jvm)
			String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
			try (FileOutputStream os = new FileOutputStream(new File(pidPath))) {
				os.write(pid.getBytes());
			}

			// Log started
			if (logger.isInfoEnabled())
				logger.info("Application started on " + (keystore != null ? "HTTPS" : "HTTP") + " port " + port + " with PID " + pid);
		} catch (Exception ex) {
			// Log fatal error
			if (logger.isErrorEnabled())
				logger.error("Application stopped because of unexpected error.", ex);
		}
	}

	private static final String ip(Request request) throws Exception {
		String ip = request.headers("X-Real-IP");
		if (StringUtils.isNotEmpty(ip))
			return ip;
		ip = request.headers("X-Forwarded-For");
		if (StringUtils.isNotEmpty(ip))
			return ip.split(",")[0].trim();
		return request.ip();
	}

	private static final String mimetype(Request request, Response response, BiFunction<String, String, String> settings) {
		// File extension param
		String param = request.params(":extension");
		// Checked file extension
		String extension = param.substring(param.indexOf(".") + 1);
		// Associated MIME type
		String mimetype = settings.apply("mimetype." + extension, null);
		if (mimetype == null) {
			response.status(HttpServletResponse.SC_NOT_FOUND);
			return "";
		}
		response.status(HttpServletResponse.SC_OK);
		return mimetype;
	}

	private static final class MoneyRates implements Route {

		private final String url;
		private final long refreshInterval;
		private long refreshTime = 0;
		private JsonObject result = null;

		public MoneyRates(BiFunction<String, String, String> settings) {
			super();
			this.url = settings.apply("utils.moneyrates.url", "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml");
			this.refreshInterval = Integer.parseInt(settings.apply("utils.moneyrates.interval", "1")) * 24 * 60 * 60 * 1000;
		}

		@Override
		public Object handle(Request request, Response response) throws Exception {
			try {
				// Attendre une journée entre chaque raffraichissement
				if (this.result == null || (System.currentTimeMillis() - this.refreshTime) > this.refreshInterval) {
					if (logger.isInfoEnabled())
						logger.info("[moneyrates] Refreshing...");
					// http://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml
					// http://www.ecb.europa.eu/stats/exchange/eurofxref/html/index.en.html
					DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
					DocumentBuilder builder = factory.newDocumentBuilder();
					Document doc = builder.parse(this.url);
					NodeList nodes = doc.getElementsByTagName("Cube");
					this.result = new JsonObject();
					this.refreshTime = System.currentTimeMillis();
					for (int i = 0; i < nodes.getLength(); i++) {
						Node node = nodes.item(i);
						Node currency = node.getAttributes().getNamedItem("currency");
						Node rate = node.getAttributes().getNamedItem("rate");
						if (currency != null)
							this.result.addProperty(currency.getNodeValue(), Double.valueOf(rate.getNodeValue()));
					}
					if (logger.isInfoEnabled())
						logger.info("[moneyrates] Refresh completed.");
				}

				// Renvoyer le résultat au format JSON
				String json = new Gson().toJson(this.result);
				response.header("X-DISCLAIMER", "Test API. Use at your own risk");
				response.header("Access-Control-Allow-Origin", "*");
				response.header("Content-Disposition", "inline; filename=\"moneyrates.json\"");
				response.header("Content-Length", Integer.toString(json.length()));
				response.type("application/json");
				return json;

			} catch (Exception ex) {
				// Annuler
				this.result = null;
				this.refreshTime = 0;
				// Tracer
				if (logger.isErrorEnabled())
					logger.error("[moneyrates] Unexpected error while refreshing.", ex);
				// Retour
				Spark.halt(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error"); // 500
				return null;
			}
		}
	}

	private static final class IBlockList implements Route {

		private final List<String> urls;
		private final File file;
		private final long refreshInterval;
		private long refreshTime = 0;

		public IBlockList(BiFunction<String, String, String> settings) {
			super();
			this.urls = new ArrayList<>();
			this.file = new File(settings.apply("utils.iblocklist.file", "iblocklist.txt.gz"));
			this.refreshInterval = Integer.parseInt(settings.apply("utils.iblocklist.interval", "1")) * 24 * 60 * 60 * 1000;
			int index = 0;
			while (settings.apply("utils.iblocklist." + index, null) != null) {
				this.urls.add(settings.apply("utils.iblocklist." + index, null));
				index++;
			}
		}

		@Override
		public Object handle(Request request, Response response) throws Exception {
			try {
				// Demander un fichier temporaire
				byte[] buffer = new byte[1024 * 1024];

				// Attendre une journée entre chaque raffraichissement
				if ((System.currentTimeMillis() - this.refreshTime) > this.refreshInterval) {
					if (logger.isInfoEnabled())
						logger.info("[iblocklist] Refreshing " + this.file.getAbsolutePath() + "...");
					this.file.delete();
					try (OutputStream os = new GZIPOutputStream(new FileOutputStream(this.file, false))) {
						for (String url : this.urls) {
							if (logger.isInfoEnabled())
								logger.info("[iblocklist] Adding " + url + "...");
							try (InputStream is = new GZIPInputStream(new URL(url).openStream())) {
								copy(is, os, buffer);
							}
						}
						this.refreshTime = System.currentTimeMillis();
					}
					if (logger.isInfoEnabled())
						logger.info("[iblocklist] Refresh completed.");
				}

				// Renvoyer le résultat du fichier concaténé
				response.header("Content-Disposition", "inline; filename=\"" + this.file.getName() + "\"");
				response.header("Content-Length", Long.toString(this.file.length()));
				response.type("application/x-gzip");
				try (InputStream is = new FileInputStream(this.file)) {
					try (OutputStream os = response.raw().getOutputStream()) {
						copy(is, os, buffer);
					}
					return null;
				}

			} catch (IOException ex) {
				// Annuler
				this.file.delete();
				this.refreshTime = 0;
				// Tracer
				if (logger.isErrorEnabled())
					logger.error("[iblocklist] Unexpected error while refreshing.", ex);
				// Retour
				Spark.halt(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error"); // 500
				return null;
			}
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

				// Récupérer le type MIME uniquement si nécessaire
				Supplier<String> mimetype = () -> {
					String extension = path.substring(path.lastIndexOf(".") + 1);
					return this.settings.apply("mimetype." + extension, "application/octet-stream");
				};

				// Renvoyer ou non le fichier selon le cache
				sendCacheable(request.raw(), response.raw(), file, mimetype);
				if (logger.isTraceEnabled())
					logger.trace(response.status() + " : " + path);

				// Dans les cas, la réponse est déjà terminée
				return "";

			} catch (Exception ex) {
				if (logger.isErrorEnabled())
					logger.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR + " : " + path, ex);
				Spark.halt(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error"); // 500
				return null;
			}
		}

		private static final boolean sendCacheable(HttpServletRequest request, HttpServletResponse response, File file, Supplier<String> mimetype) throws IOException {
			// La date de modification du fichier sert de date pour le cache
			Date fileDate = new Date(file.lastModified());
			String lastModified = CACHE_DATE_FORMAT.format(fileDate);
			String etag = sha1(lastModified);

			// En-têtes correspondantes aux infos calculées du cache
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Etag", etag);
			response.setHeader("Last-Modified", lastModified);

			//1er type de cache : If-None-Match :""9e3fa9259d22837a4e72fe8b69112968b88e3cca""
			String ifNoneMatch = request.getHeader("If-None-Match");
			if (ifNoneMatch == null || !ifNoneMatch.equals(etag)) {
				//2ème type de cache : If-Modified-Since :"Mon, 16 Mar 2015 07:42:10 GMT"
				String ifModifiedSince = request.getHeader("If-Modified-Since");
				if (ifModifiedSince == null || !ifModifiedSince.equals(lastModified)) {
					// Tant pis, pas de cache
					response.setHeader("Date", lastModified);
					response.setContentType(Optional.ofNullable(mimetype).map(Supplier::get).orElse("application/octet-stream"));
					response.setStatus(HttpServletResponse.SC_OK);
					// Envoyer le fichier demandé
					try (InputStream is = new FileInputStream(file);
							OutputStream os = response.getOutputStream()) {
						copy(is, os, new byte[1024 * 1024]);
					} catch (EofException ex) {
						// Requête interrompue par le client
					}
					return false;
				}
			}

			// OK, la donnée en cache semble à jour, on renvoie le statut 304 (Not Modified)
			response.setContentLength(0);
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return true;
		}

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
