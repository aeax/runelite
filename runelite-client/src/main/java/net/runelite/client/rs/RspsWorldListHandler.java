package net.runelite.client.rs;

import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.runelite.http.api.worlds.WorldType; // Assuming this is the correct import

/**
 * Helper class to generate a valid world list format for RSPS
 */
@Slf4j
public class RspsWorldListHandler {

    // Simple POJO to hold world data for JSON serialization
    public static class POJORspsWorld {
        public int id;
        public EnumSet<WorldType> types; // Using EnumSet if WorldType is accessible
        public String address;
        public String activity;
        public int location;
        public int players;

        // Constructor to match net.runelite.http.api.worlds.World if possible
        public POJORspsWorld(int id, EnumSet<WorldType> types, String address, String activity, int location, int players) {
            this.id = id;
            this.types = types;
            this.address = address;
            this.activity = activity;
            this.location = location;
            this.players = players;
        }
    }

    public static byte[] generateWorldListData(String rspsHost, int rspsPort) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            int worldCount = 1;

            // World data
            int worldId = 255;
            int properties = 1; // Flag for members world
            String activity = "Gnome";
            int location = 0; // Location (0 = USA)
            int population = 0;

            int stringBytes = rspsHost.getBytes(StandardCharsets.UTF_8).length + activity.getBytes(StandardCharsets.UTF_8).length + 2; // +2 for null terminators
            int worldEntrySize = 2 + 4 + stringBytes + 1 + 2;
            int payloadSize = 2 + worldEntrySize;

            // Write payload size
            dos.writeInt(payloadSize);
            
            // Write world count
            dos.writeShort(worldCount);
            
            // Write world data
            dos.writeShort(worldId);
            dos.writeInt(properties);
            writeJagexString(dos, rspsHost);
            writeJagexString(dos, activity);
            dos.writeByte(location);
            dos.writeShort(population);
            
            dos.flush();
            byte[] worldData = baos.toByteArray();
            
            log.debug("Generated RSPS world list data ({} bytes) for host: {}", worldData.length, rspsHost);
            return worldData;
        } catch (IOException e) {
            log.error("Error generating world list data", e);
            return new byte[0];
        }
    }
    
    private static void writeJagexString(DataOutputStream dos, String str) throws IOException {
        dos.write(str.getBytes(StandardCharsets.UTF_8));
        dos.writeByte(0);
    }

    public static List<POJORspsWorld> parseWorldListDataToPojo(byte[] worldData) {
        List<POJORspsWorld> worlds = new ArrayList<>();
        if (worldData == null || worldData.length < 6) { 
            log.warn("World list data is too short to parse.");
            return worlds;
        }

        try (java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(worldData))) {
            int payloadSize = dis.readInt();
            int worldCount = dis.readShort();

            log.debug("Parsing world list data to POJO: PayloadSize={}, WorldCount={}", payloadSize, worldCount);

            for (int i = 0; i < worldCount; i++) {
                int worldId = dis.readShort();
                int propertiesMask = dis.readInt(); 
                String host = readJagexString(dis);
                String activity = readJagexString(dis);
                int location = dis.readUnsignedByte();
                int population = dis.readShort(); 

                EnumSet<WorldType> worldTypes = EnumSet.noneOf(WorldType.class);
                for (WorldType type : WorldType.values()) {
                    if ((propertiesMask & (1 << type.ordinal())) != 0) {
                        worldTypes.add(type);
                    }
                }
                if (worldTypes.isEmpty() && propertiesMask != 0) {
                    log.warn("World ID {} has propertiesMask {} but no WorldTypes matched. Check WorldType enum.", worldId, propertiesMask);
                    if ((propertiesMask & 1) != 0) worldTypes.add(WorldType.MEMBERS);
                }
                 if (worldTypes.isEmpty() && propertiesMask == 0) {
                     log.debug("World ID {} has no properties.", worldId);
                 }


                POJORspsWorld world = new POJORspsWorld(
                    worldId,
                    worldTypes,
                    host,
                    activity,
                    location,
                    population
                );
                worlds.add(world);
                log.debug("Parsed POJO world: ID={}, Host={}, Activity={}, Location={}, Population={}, Types={}", worldId, host, activity, location, population, worldTypes);
            }
        } catch (IOException e) {
            log.error("Error parsing world list data to POJO", e);
        }
        return worlds;
    }

    private static String readJagexString(java.io.DataInputStream dis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte b;
        while ((b = dis.readByte()) != 0) {
            baos.write(b);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
} 