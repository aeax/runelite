package net.runelite.client.rs;

import org.objectweb.asm.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GnomePatcher {
    // Original RSA modulus.. edu says not needed,,.. idk tho
    private static final String ORIGINAL_JAGEX_RSA_MODULUS_HEX = "86e8427690ebc5e5cc9b6b3336a1f7af648af5bcf44a8057b13a05934a356697924662f3dc12214aa0ddcb0030e0e53c3fc937e50424a85a5ae1ddfa7712e2971cbc0c6a7c1ed5a6602c4d838b3ec9cd663b6e065923456bc76ea9974bef518ddf4caac9e9cf6ae8090345598fd2f8c55ef7a2e8f01770582bf8cfcc4e668ae9";
    private final String targetRspsHost;
    private final String targetRspsRsaModulusHex;

    public GnomePatcher(String rspsHost, String rspsRsaModulusHex) {
        this.targetRspsHost = rspsHost;
        this.targetRspsRsaModulusHex = rspsRsaModulusHex;
        log.info("GnomePatcher initialized. Target Host: {}, Target RSPS RSA Modulus (prefix): {}...",
                rspsHost,
                rspsRsaModulusHex.substring(0, Math.min(10, rspsRsaModulusHex.length())));
    }
    public byte[] patchGamepack(byte[] originalGamepackBytes) throws IOException {
        if (originalGamepackBytes == null || originalGamepackBytes.length == 0) {
            throw new IllegalArgumentException("Original gamepack bytes cannot be null or empty.");
        }

        JarInputStream jis = new JarInputStream(new ByteArrayInputStream(originalGamepackBytes));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos);
        JarEntry entry;

        log.debug("Starting gamepack patching process...");
        int patchedClasses = 0;

        while ((entry = jis.getNextJarEntry()) != null) {
            jos.putNextEntry(new JarEntry(entry.getName()));
            byte[] currentEntryBytes = readEntry(jis);

            if (entry.getName().endsWith(".class")) {
                // Patch Jagex domain... shjambles
                byte[] patchedBytes = replaceDomains(currentEntryBytes);
                patchedBytes = patchRsaModulus(patchedBytes);
                
                jos.write(patchedBytes);
                patchedClasses++;
            } else {
                jos.write(currentEntryBytes);
            }
            jos.closeEntry();
        }
        
        log.debug("Gamepack patching complete. Processed {} class files.", patchedClasses);
        
        jis.close();
        jos.close();
        return baos.toByteArray();
    }
    private byte[] replaceDomains(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        
        // Domain name patching class visitor
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            String str = (String) value;
                            if (str.contains("runescape.com") || str.contains("jagex.com")) {
                                super.visitLdcInsn(targetRspsHost);
                                return;
                            }
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        
        return cw.toByteArray();
    }

    /**
     * Patches RSA modulus for connection encryption.
     */
    private byte[] patchRsaModulus(byte[] classBytes) {
        // Try direct string replacement first
        byte[] exactPatched = findAndReplaceExactModulus(classBytes);
        if (!Arrays.equals(exactPatched, classBytes)) {
            return exactPatched;
        }

        // If direct replacement failed, use ASM to find and patch modulus
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        
        final boolean[] patched = {false};
        
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        // Check for RSA modulus string
                        if (value instanceof String) {
                            String str = (String) value;
                            // Match exact modulus
                            if (str.equals(ORIGINAL_JAGEX_RSA_MODULUS_HEX)) {
                                log.info("RSA PATCH: Found and replaced exact RSA modulus");
                                super.visitLdcInsn(targetRspsRsaModulusHex);
                                patched[0] = true;
                                return;
                            }
                            
                            // Aggressive matching for longer hex strings
                            if (str.length() > 200 && isHexString(str)) {
                                log.info("RSA PATCH: Found potential RSA modulus string (length={})", str.length());
                                super.visitLdcInsn(targetRspsRsaModulusHex);
                                patched[0] = true;
                                return;
                            }
                        }
                        super.visitLdcInsn(value);
                    }
                    
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        // Target BigInteger constructors in client class
                        if (owner.equals("java/math/BigInteger") && name.equals("<init>") && 
                            descriptor.equals("(Ljava/lang/String;)V")) {
                            // Only patch in client class and login methods
                            String className = cr.getClassName();
                            if (className.equals("client") || className.contains("login")) {
                                // Pop original argument and replace with our modulus
                                super.visitInsn(Opcodes.POP);
                                super.visitLdcInsn(targetRspsRsaModulusHex);
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                patched[0] = true;
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        
        if (patched[0]) {
            log.info("RSA patched via ASM");
        }
        
        return cw.toByteArray();
    }
    
    /**
     * Finds and replaces RSA modulus directly in byte array.
     */
    private byte[] findAndReplaceExactModulus(byte[] bytes) {
        byte[] modBytes = ORIGINAL_JAGEX_RSA_MODULUS_HEX.getBytes(StandardCharsets.UTF_8);
        int index = indexOf(bytes, modBytes);
        if (index != -1) {
            byte[] patched = bytes.clone();
            byte[] newModBytes = targetRspsRsaModulusHex.getBytes(StandardCharsets.UTF_8);
            // Make sure we don't overflow
            System.arraycopy(newModBytes, 0, patched, index, Math.min(newModBytes.length, modBytes.length));
            log.info("RSA PATCH: Replaced modulus at byte offset {}", index);
            return patched;
        }
        return bytes;
    }


    private boolean isHexString(String str) {
        for (char c : str.toCharArray()) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Finds a byte pattern in a larger byte array.
     */
    private int indexOf(byte[] source, byte[] pattern) {
        for (int i = 0; i <= source.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (source[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Reads a JAR entry into a byte array.
     */
    private static byte[] readEntry(InputStream inputStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = inputStream.read(buffer)) != -1) {
            baos.write(buffer, 0, n);
        }
        return baos.toByteArray();
    }
}