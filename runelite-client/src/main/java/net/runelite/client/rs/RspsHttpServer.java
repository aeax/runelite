package net.runelite.client.rs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Simple HTTP server to handle RSPS-specific requests like worldlist.ws
 */
@Slf4j
public class RspsHttpServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String WORLDLIST_FILE_PATH = "C:/Users/CLG/Desktop/rl/rsprox/slr.ws";
    private static final boolean ALWAYS_GENERATE_WORLDLIST = true;
    
    private HttpServer server;
    private final String rspsHost;
    private final int rspsPort;
    private final int httpPort;
    private final boolean useWorldListFile;
    private final byte[] worldListData;
    
    public RspsHttpServer(String rspsHost, int rspsPort) {
        this(rspsHost, rspsPort, DEFAULT_PORT);
    }
    
    public RspsHttpServer(String rspsHost, int rspsPort, int httpPort) {
        this.rspsHost = rspsHost;
        this.rspsPort = rspsPort;
        this.httpPort = httpPort;
        
        // Initialize useWorldListFile and worldListData using local variables first
        boolean useFile = false;
        byte[] fileData = null;
        
        // Check if worldlist file exists and load it
        File worldListFile = new File(WORLDLIST_FILE_PATH);
        
        if (!ALWAYS_GENERATE_WORLDLIST && worldListFile.exists() && worldListFile.isFile()) {
            try {
                fileData = Files.readAllBytes(Paths.get(WORLDLIST_FILE_PATH));
                useFile = true;
                log.info("Loaded worldlist data from file: {} ({} bytes)", WORLDLIST_FILE_PATH, fileData.length);
            } catch (IOException e) {
                log.error("Failed to load worldlist file: {}", e.getMessage());
            }
        } else {
            log.info("Generating worldlist data dynamically; ignoring local file: {}", WORLDLIST_FILE_PATH);
        }
        
        // Now assign to final fields only once
        this.useWorldListFile = useFile;
        this.worldListData = fileData;
    }
    
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            server.createContext("/worldlist.ws", new WorldListHandler());
            server.createContext("/worldlist.json", new WorldListJsonHandler());
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            log.info("RSPS HTTP server started on port {}", httpPort);
            
            if (useWorldListFile) {
                log.info("Using worldlist data from file: {} ({} bytes)", WORLDLIST_FILE_PATH, worldListData.length);
            } else {
                log.info("Using dynamically generated worldlist data");
            }
        } catch (IOException e) {
            log.error("Failed to start RSPS HTTP server", e);
        }
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("RSPS HTTP server stopped");
        }
    }
    
    public int getPort() {
        return httpPort;
    }
    
    public String getWorldListUrl() {
        return "http://127.0.0.1:" + httpPort + "/worldlist.ws";
    }
    
    private class WorldListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("DEBUG: Received worldlist.ws request from client. Request URI: " + exchange.getRequestURI());
            System.out.println("DEBUG: Request method: " + exchange.getRequestMethod());
            System.out.println("DEBUG: Remote address: " + exchange.getRemoteAddress());
            
            byte[] response;
            
            // Use file data if available, otherwise generate dynamically
            if (useWorldListFile && worldListData != null) {
                response = worldListData;
                System.out.println("DEBUG: Serving worldlist from file (" + response.length + " bytes)");
                // Print first few bytes for debugging
                StringBuilder hexDump = new StringBuilder();
                for (int i = 0; i < Math.min(20, response.length); i++) {
                    hexDump.append(String.format("%02X ", response[i]));
                }
                System.out.println("DEBUG: First 20 bytes of worldlist: " + hexDump);
            } else {
                response = RspsWorldListHandler.generateWorldListData(rspsHost, rspsPort);
                System.out.println("DEBUG: Serving dynamically generated worldlist (" + response.length + " bytes)");
            }
            
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, response.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            
            log.debug("Served world list data ({} bytes) for {}", response.length, exchange.getRequestURI());
        }
    }

    private class WorldListJsonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] worldListDataBytes;
            if (useWorldListFile && worldListData != null) {
                worldListDataBytes = worldListData;
            } else {
                worldListDataBytes = RspsWorldListHandler.generateWorldListData(rspsHost, rspsPort);
            }

            List<RspsWorldListHandler.POJORspsWorld> pojoWorlds = RspsWorldListHandler.parseWorldListDataToPojo(worldListDataBytes);

            Map<String, List<RspsWorldListHandler.POJORspsWorld>> resultContainer = new HashMap<>();
            resultContainer.put("worlds", pojoWorlds);
            
            String jsonResponse = net.runelite.http.api.RuneLiteAPI.GSON.toJson(resultContainer);
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
            log.debug("Served JSON world list data ({} bytes) for {}", responseBytes.length, exchange.getRequestURI());
        }
    }
} 