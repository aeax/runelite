/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
 * Copyright (c) 2019 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.rs;

import com.google.common.base.Strings;
import java.applet.Applet;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.RuntimeConfig;
import net.runelite.client.RuntimeConfigLoader;
import net.runelite.client.ui.FatalErrorDialog;
import net.runelite.client.ui.SplashScreen;
import net.runelite.http.api.worlds.World;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
@SuppressWarnings({"deprecation"})
public class ClientLoader implements Supplier<Client>
{
	private static final int NUM_ATTEMPTS = 6;
	private static final String RSPS_REVISION = "228.2";
	// RSPS Configuration Constants
	private static final String RSPS_HOST = "127.0.0.1";
	private static final String RSPS_PORT = "43594";
	private static final String RSPS_WORLD_ID = "0";
	private static final String OVERRIDE_JAV_CONFIG_URL = "https://client.blurite.io/jav_local_228.ws";
	private static final String GITHUB_OSRS_228_GAMEPACK_URL = "https://github.com/runetech/osrs-gamepacks/raw/master/gamepacks/osrs-228.jar";
	private static final String RSPS_RSA_MODULUS = "d48583219eb5bafdd5dbf2f3561c84b83c5966e8f6ba546adba42437acc6e42402052e704261a549a7cfad45dd77cb9eb32e830202dfd6b60b5551d8b040f0bbc4c9f564ae711d4335696f6427f60767c6dfcb586355b3a1170e4c0be30235abc5659f183d98d0171ad5f234e3429c178b0bc4ac6b4149484720abde9a39b07f";

	private final ClientConfigLoader clientConfigLoader;
	private final WorldSupplier worldSupplier;
	private final RuntimeConfigLoader runtimeConfigLoader;
	private final String javConfigUrl;
	private final OkHttpClient okHttpClient;

	private Object client;

	// Add a field to store the HTTP server instance
	private static RspsHttpServer rspsHttpServer;

	public ClientLoader(OkHttpClient okHttpClient, RuntimeConfigLoader runtimeConfigLoader, String javConfigUrl)
	{
		this.okHttpClient = okHttpClient;
		this.clientConfigLoader = new ClientConfigLoader(okHttpClient);
		this.worldSupplier = new WorldSupplier(okHttpClient);
		this.runtimeConfigLoader = runtimeConfigLoader;
		this.javConfigUrl = javConfigUrl;
	}

	@Override
	public synchronized Client get()
	{
		if (client == null)
		{
			client = doLoad();
		}

		if (client instanceof Throwable)
		{
			throw new RuntimeException((Throwable) client);
		}
		return (Client) client;
	}

	private Object doLoad()
	{
		try
		{
			SplashScreen.stage(0, null, "Fetching applet viewer config");
			RSConfig config = downloadConfig();

			SplashScreen.stage(.3, "Starting", "Starting Old School RuneScape");

			Client rs = loadClient(config);

			SplashScreen.stage(.4, null, "Starting core classes");

			return rs;
		}
		catch (OutageException e)
		{
			return e;
		}
		catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException | SecurityException e)
		{
			log.error("Error loading RS!", e);

			if (!checkOutages())
			{
				SwingUtilities.invokeLater(() -> FatalErrorDialog.showNetErrorWindow("loading the client", e));
			}
			return e;
		}
	}

	private RSConfig downloadConfig() throws IOException
	{
		HttpUrl urlForAppletParams = HttpUrl.get(OVERRIDE_JAV_CONFIG_URL);
		log.info("RSPS Mode: Attempting to download applet parameters from: {}", urlForAppletParams);
		IOException lastException = null;

		for (int attempt = 0; attempt < NUM_ATTEMPTS; attempt++)
		{
			try
			{
				log.info("Attempt {} to download jav_config (for applet params) from {}", attempt + 1, urlForAppletParams);
				RSConfig configFromRspsSource = clientConfigLoader.fetch(urlForAppletParams);

				log.info("Successfully fetched applet parameters from {}. Initial class: '{}'", urlForAppletParams, configFromRspsSource.getInitialClass());
				for (Map.Entry<String, String> entry : configFromRspsSource.getAppletProperties().entrySet()) {
					log.debug("  RSPS Source Param: '{}' = '{}'", entry.getKey(), entry.getValue());
				}

				// Regardless of where applet params came from, download the specific GitHub rev 228 gamepack
				String gamepackDownloadUrl = GITHUB_OSRS_228_GAMEPACK_URL;
				log.info("Downloading specific rev 228 gamepack from: {}", gamepackDownloadUrl);

				Request gamepackRequest = new Request.Builder().url(gamepackDownloadUrl).build();
				byte[] originalGamepackBytes;
				try (Response gamepackResponse = this.okHttpClient.newCall(gamepackRequest).execute()) {
					if (!gamepackResponse.isSuccessful() || gamepackResponse.body() == null) {
						throw new IOException("Failed to download specific gamepack from " + gamepackDownloadUrl + ": " + gamepackResponse.message());
					}
					originalGamepackBytes = gamepackResponse.body().bytes();
				}
				log.info("Downloaded specific rev 228 gamepack ({} bytes) from {}", originalGamepackBytes.length, gamepackDownloadUrl);

				// Patch the downloaded gamepack
				GnomePatcher gamepackPatcher = new GnomePatcher(RSPS_HOST, RSPS_RSA_MODULUS);
				byte[] patchedGamepackBytes = gamepackPatcher.patchGamepack(originalGamepackBytes);
				log.info("Patched gamepack size: {} bytes", patchedGamepackBytes.length);

				// Save patched gamepack to a temporary file
				File tempDir = new File(System.getProperty("java.io.tmpdir"), "runelite-rsps-patch");
				if (!tempDir.exists() && !tempDir.mkdirs()) {
					throw new IOException("Failed to create temp directory for patched gamepack: " + tempDir.getAbsolutePath());
				}
				File patchedJarFile = new File(tempDir, "rsps_patched_github_228_gamepack_" + System.currentTimeMillis() + ".jar");
				patchedJarFile.deleteOnExit();
				try (FileOutputStream fos = new FileOutputStream(patchedJarFile)) {
					fos.write(patchedGamepackBytes);
				}
				log.info("Saved patched gamepack to: {}", patchedJarFile.getAbsolutePath());
				String patchedJarParentUrl = patchedJarFile.getParentFile().toURI().toURL().toString();

				// Apply RSPS specific parameters using configFromRspsSource as the base
				applyRspsAppletParameters(configFromRspsSource, patchedJarParentUrl, patchedJarFile.getName());

				log.info("RSConfig (using RSPS source for applet params, GitHub rev228 for gamepack) modified. New codebase: {}, New initial_jar: {}",
						configFromRspsSource.getClassLoaderProperties().get("codebase"), configFromRspsSource.getClassLoaderProperties().get("initial_jar"));

				return configFromRspsSource; // Return the config object which now has modified applet/classloader properties
			}
			catch (IOException e)
			{
				lastException = e;
				log.warn("Attempt {} failed to download or process RSPS config/gamepack: {}", attempt + 1, e.getMessage());
				if (checkOutages()) {
					throw new OutageException(e);
				}
				if (urlForAppletParams.toString().equals(OVERRIDE_JAV_CONFIG_URL)) {
					log.warn("Failed to get jav_config (for applet params) from the override URL {}. Will retry or fall back.", OVERRIDE_JAV_CONFIG_URL);
				} else { // This case should not be hit with current logic but kept for safety from old structure
					String host = worldSupplier.get().getAddress();
					urlForAppletParams = urlForAppletParams.newBuilder().host(host).build(); // Try next world for Jagex if it was a Jagex URL
				}
			}
		}

		try
		{
			RSConfig fallbackAppletParamsConfig = downloadFallbackConfig();
			log.info("Fetched Jagex fallback config for applet parameters base.");

			String gamepackDownloadUrl = GITHUB_OSRS_228_GAMEPACK_URL;
			log.info("Downloading specific rev 228 gamepack from: {} (for fallback path)", gamepackDownloadUrl);

			Request gamepackRequest = new Request.Builder().url(gamepackDownloadUrl).build();
			byte[] originalGamepackBytes;
			try (Response gamepackResponse = this.okHttpClient.newCall(gamepackRequest).execute()) {
				if (!gamepackResponse.isSuccessful() || gamepackResponse.body() == null) {
					throw new IOException("Failed to download specific gamepack from " + gamepackDownloadUrl + " (for fallback path): " + gamepackResponse.message());
				}
				originalGamepackBytes = gamepackResponse.body().bytes();
			}
			log.info("Downloaded specific rev 228 gamepack ({} bytes) from {} (for fallback path)", originalGamepackBytes.length, gamepackDownloadUrl);

			GnomePatcher gamepackPatcher = new GnomePatcher(RSPS_HOST, RSPS_RSA_MODULUS);
			byte[] patchedGamepackBytes = gamepackPatcher.patchGamepack(originalGamepackBytes);
			log.info("Patched gamepack (fallback path using GitHub gamepack) size: {} bytes", patchedGamepackBytes.length);

			File tempDir = new File(System.getProperty("java.io.tmpdir"), "runelite-rsps-patch");
			if (!tempDir.exists() && !tempDir.mkdirs()) {
				throw new IOException("Failed to create temp directory for patched gamepack (fallback path): " + tempDir.getAbsolutePath());
			}
			File patchedJarFile = new File(tempDir, "rsps_patched_github_228_fallback_gamepack_" + System.currentTimeMillis() + ".jar");
			patchedJarFile.deleteOnExit();
			try (FileOutputStream fos = new FileOutputStream(patchedJarFile)) {
				fos.write(patchedGamepackBytes);
			}
			log.info("Saved patched gamepack (fallback path using GitHub gamepack) to: {}", patchedJarFile.getAbsolutePath());
			String patchedJarParentUrl = patchedJarFile.getParentFile().toURI().toURL().toString();

			applyRspsAppletParameters(fallbackAppletParamsConfig, patchedJarParentUrl, patchedJarFile.getName());
			log.info("RSConfig (fallback from Jagex for applet params, GitHub rev228 for gamepack, then RSPS patched) modified. New codebase: {}, New initial_jar: {}",
					fallbackAppletParamsConfig.getClassLoaderProperties().get("codebase"), fallbackAppletParamsConfig.getClassLoaderProperties().get("initial_jar"));
			return fallbackAppletParamsConfig;
		}
		catch (IOException ex)
		{
			log.error("Error during fallback RSPS setup: {}", ex.getMessage(), ex);
			if (lastException != null) throw lastException; // Prefer original exception from primary path if it exists
			throw ex;
		}
	}

	private void applyRspsAppletParameters(RSConfig rsConfig, String patchedCodebaseUrl, String patchedInitialJarName) {
		Map<String, String> classLoaderProps = rsConfig.getClassLoaderProperties();
		classLoaderProps.put("codebase", patchedCodebaseUrl);
		classLoaderProps.put("initial_jar", patchedInitialJarName);

		Map<String, String> appletProps = rsConfig.getAppletProperties();
				RSPS_HOST, RSPS_PORT, RSPS_WORLD_ID, RSPS_REVISION);

		// Log original parameters before overriding for debugging
		if (log.isDebugEnabled()) { // Avoids creating map if not debugging
			log.debug("Original applet parameters before override:");
			for (Map.Entry<String, String> entry : new java.util.HashMap<>(appletProps).entrySet()) {
				log.debug("  Original Param: '{}' = '{}'", entry.getKey(), entry.getValue());
			}
		}

		appletProps.put("codebase", patchedCodebaseUrl);
		appletProps.put("initial_jar", patchedInitialJarName);
		appletProps.put("archive", patchedInitialJarName);
		appletProps.put("4", RSPS_PORT); // Update JS5/game connection port to gnome port

		// Start local HTTP server for worldlist.ws if not already running
		if (rspsHttpServer == null) {
			int rspsPortNum = Integer.parseInt(RSPS_PORT);
			rspsHttpServer = new RspsHttpServer(RSPS_HOST, rspsPortNum);
			rspsHttpServer.start();
			log.info("Started local HTTP server for RSPS on port {}", rspsHttpServer.getPort());
			// Set system property for WorldClient to pick up the JSON endpoint
			System.setProperty("runelite.rsps.worldlist.url", "http://127.0.0.1:" + rspsHttpServer.getPort() + "/worldlist.json");
			log.info("Set RSPS worldlist system property to: {}", System.getProperty("runelite.rsps.worldlist.url"));
		}
		
		// Get worldlist.ws URL from the HTTP server (for param 17, if needed by client directly)
		String worldListWsUrl = rspsHttpServer.getWorldListUrl(); 
		log.info("Using local worldlist.ws URL: {}", worldListWsUrl);

		// Ensure initial_class is set, preferring value from parsed jav_config if not in applet map
		if (!appletProps.containsKey("initial_class")) {
			if (!Strings.isNullOrEmpty(rsConfig.getInitialClass())) {
				log.info("Initial_class not in applet_properties map, but found in RSConfig.getInitialClass(): '{}'. Adding to map.", rsConfig.getInitialClass());
				appletProps.put("initial_class", rsConfig.getInitialClass());
			} else {
				log.warn("Initial_class not found in base config's applet_properties or RSConfig.getInitialClass(), setting to 'client' as default.");
				appletProps.put("initial_class", "client");
			}
		} else {
			log.info("Initial_class found in applet_properties map: '{}'", appletProps.get("initial_class"));
		}

		appletProps.put("worldid", RSPS_WORLD_ID);
		appletProps.put("server_ip", RSPS_HOST);
		appletProps.put("address", RSPS_HOST);
		appletProps.put("worldhost", RSPS_HOST);
		appletProps.put("server_port", RSPS_PORT);
		appletProps.put("port", RSPS_PORT);
		appletProps.put("title", "Patched RSPS Client (Rev " + RSPS_REVISION + ")");
		appletProps.put("param_25", RSPS_REVISION); // Ensure correct revision for the client

		// Minimal localization for critical parameters that might point to external services
		String[] paramsToLocalizeIfPresent = {"jav_config_url", "worldlist_ws_url", "slr_ws_url", "adverturl", "termsurl", "privacyurl"};
		for (String paramKey : paramsToLocalizeIfPresent) {
			if (appletProps.containsKey(paramKey)) {
				// If it's the worldlist, use our local server (binary .ws endpoint)
				if (paramKey.equals("worldlist_ws_url")) {
					appletProps.put(paramKey, worldListWsUrl); // Use the .ws binary URL here
					log.info("Set worldlist_ws_url to local server: {}", worldListWsUrl);
				} else {
					// Create a dummy local URL for other parameters
					appletProps.put(paramKey, "http://" + RSPS_HOST + "/rsps_dummy_path/" + paramKey.replace("_url",""));
					log.info("Localized param '{}' to point to a dummy local URL.", paramKey);
				}
			}
		}

		// Force override specific numeric parameters that often contain external Jagex URLs
		// These might have been set by the RSPS's jav_config but need to be strictly local/dummy.
		String dummyRspsUrlPrefix = "http://" + RSPS_HOST + "/rsps_dummy_path/";
		appletProps.put("11", dummyRspsUrlPrefix + "auth_jagex_com"); // Typically https://auth.jagex.com/
		appletProps.put("13", dummyRspsUrlPrefix + "dummy_domain_suffix"); // Force override for .runescape.com or similar domain suffixes
		// Use our local world list server (.ws binary endpoint) for param 17
		appletProps.put("17", worldListWsUrl);
		appletProps.put("20", dummyRspsUrlPrefix + "social_auth_jagex_com"); // Typically https://social.auth.jagex.com/
		appletProps.put("22", dummyRspsUrlPrefix + "auth_runescape_com"); // Typically https://auth.runescape.com/
		appletProps.put("28", dummyRspsUrlPrefix + "account_jagex_com"); // Typically https://account.jagex.com/
		// param_9 is often a session key or similar, leave as is unless proven problematic
		// param_19 is often a Google client ID, likely fine, but could be dummied if issues persist
		// param_2 is often payments.jagex.com
		appletProps.put("2", dummyRspsUrlPrefix + "payments_jagex_com");

		log.info("Applied explicit overrides for numeric parameters to point to dummy local URLs.");

		if (log.isInfoEnabled()) { // Check if info level is enabled before iterating and logging
			log.info("Final Applet properties after RSPS specific overrides:");
			for (Map.Entry<String, String> entry : appletProps.entrySet()) {
				log.info("  Final Param: '{}' = '{}'", entry.getKey(), entry.getValue());
			}
		}
	}

	@Nonnull
	private RSConfig downloadFallbackConfig() throws IOException
	{
		RSConfig backupConfig = clientConfigLoader.fetch(HttpUrl.get(RuneLiteProperties.getJavConfigBackup()));

		if (Strings.isNullOrEmpty(backupConfig.getCodeBase()) || Strings.isNullOrEmpty(backupConfig.getInitialJar()) || Strings.isNullOrEmpty(backupConfig.getInitialClass()))
		{
			throw new IOException("Invalid or missing jav_config from Jagex backup");
		}

		if (Strings.isNullOrEmpty(backupConfig.getRuneLiteWorldParam()))
		{
			throw new IOException("Backup config does not have RuneLite gamepack url");
		}

		// Randomize the codebase
		World world = worldSupplier.get();
		backupConfig.setCodebase("http://" + world.getAddress() + "/");

		// Update the world applet parameter
		Map<String, String> appletProperties = backupConfig.getAppletProperties();
		appletProperties.put(backupConfig.getRuneLiteWorldParam(), Integer.toString(world.getId()));

		return backupConfig;
	}

	private Client loadClient(RSConfig config) throws ClassNotFoundException, IllegalAccessException, InstantiationException
	{
		System.out.println("============ DEBUG: LOADING CLIENT ============");
		System.out.println("Initial class: " + config.getInitialClass());
		
		String initialClass = config.getInitialClass();
		Class<?> clientClass = ClientLoader.class.getClassLoader()
				.loadClass(initialClass);
				
		System.out.println("Client class loaded: " + clientClass.getName());

		Client rs = (Client) clientClass.newInstance();
		System.out.println("Client instantiated: " + rs.getClass().getName());
		
		// Add network connection monitoring
		try {
			System.out.println("DEBUG: Adding network connection monitoring...");
			
			// Set network properties for debugging
			System.setProperty("java.net.preferIPv4Stack", "true");
			System.setProperty("sun.net.client.defaultConnectTimeout", "30000");
			System.setProperty("sun.net.client.defaultReadTimeout", "30000");
			
			// Enable JVM socket debugging
			System.setProperty("javax.net.debug", "all");
			
			// Print the server connection params
			Map<String, String> props = config.getAppletProperties();
			System.out.println("DEBUG: Connection parameters:");
			System.out.println("  address: " + props.get("address"));
			System.out.println("  worldhost: " + props.get("worldhost"));
			System.out.println("  server_ip: " + props.get("server_ip"));
			System.out.println("  port: " + props.get("port"));
			System.out.println("  server_port: " + props.get("server_port"));
			System.out.println("  codebase: " + config.getCodeBase());
			
			// Try a test connection to the server
			try {
				final String host = props.get("address") != null ? props.get("address") : "127.0.0.1";
				final int port = props.get("port") != null ? Integer.parseInt(props.get("port")) : 43594;
				System.out.println("DEBUG: Testing connection to server: " + host + ":" + port);
				
				java.net.Socket testSocket = new java.net.Socket();
				testSocket.connect(new java.net.InetSocketAddress(host, port), 5000);
				System.out.println("DEBUG: Connection test successful! Socket connected: " + testSocket.isConnected());
				testSocket.close();
			} catch (Exception e) {
				System.out.println("DEBUG: Connection test failed: " + e.getMessage());
			}
		} catch (Exception e) {
			System.out.println("DEBUG: Error setting up network monitoring: " + e.getMessage());
			e.printStackTrace();
		}
		
		((Applet) rs).setStub(new RSAppletStub(config, runtimeConfigLoader));
		System.out.println("AppletStub set: " + rs.getClass().getName());

		log.info("injected-client {}", rs.getBuildID());
		System.out.println("============ DEBUG: CLIENT LOADED ============");

		return rs;
	}

	private static class OutageException extends RuntimeException
	{
		private OutageException(Throwable cause)
		{
			super(cause);
		}
	}

	private boolean checkOutages()
	{
		RuntimeConfig rtc = runtimeConfigLoader.tryGet();
		if (rtc != null)
		{
			return rtc.showOutageMessage();
		}
		return false;
	}
}
