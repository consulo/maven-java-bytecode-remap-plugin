package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Whether a class (by remapped internal name) is an interface: looked up in the classes being remapped and in the
 * project's dependency JARs. A remap target can change the kind (an IntelliJ class that is an interface in Consulo),
 * so call sites are fixed by the target's real kind.
 */
public class ClassKindIndex implements AutoCloseable {
    private final Map<String, byte[]> ownClasses;
    private final Function<String, String> ownNameMapper;
    private final List<JarFile> jars = new ArrayList<>();
    private final Map<String, Optional<Boolean>> cache = new HashMap<>();
    private Map<String, byte[]> ownByRemappedName;

    /**
     * @param ownClasses    classes of the JAR being remapped, by original internal name
     * @param ownNameMapper original internal name → remapped internal name
     */
    public ClassKindIndex(Map<String, byte[]> ownClasses, Function<String, String> ownNameMapper, Collection<File> classpath) throws IOException {
        this.ownClasses = ownClasses;
        this.ownNameMapper = ownNameMapper;
        for (File file : classpath) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                jars.add(new JarFile(file));
            }
        }
    }

    /** {@code null} if the class is unknown. */
    public synchronized Boolean isInterface(String internalName) {
        return cache.computeIfAbsent(internalName, n -> Optional.ofNullable(lookup(n))).orElse(null);
    }

    private Boolean lookup(String internalName) {
        if (ownByRemappedName == null) {
            ownByRemappedName = new HashMap<>();
            ownClasses.forEach((name, bytes) -> ownByRemappedName.put(ownNameMapper.apply(name), bytes));
        }
        byte[] own = ownByRemappedName.get(internalName);
        if (own != null) {
            return isInterface(own);
        }
        for (JarFile jar : jars) {
            ZipEntry entry = jar.getEntry(internalName + ".class");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return isInterface(in.readAllBytes());
                }
                catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Reads the access flags after the constant pool, so any class file version works (ASM rejects newer ones). */
    private static boolean isInterface(byte[] b) {
        int count = u2(b, 8);
        int offset = 10;
        for (int i = 1; i < count; i++) {
            int tag = b[offset] & 0xFF;
            switch (tag) {
                case 1 -> offset += 3 + u2(b, offset + 1); // Utf8
                case 5, 6 -> { offset += 9; i++; }         // Long, Double take two slots
                case 7, 8, 16, 19, 20 -> offset += 3;      // Class, String, MethodType, Module, Package
                case 15 -> offset += 4;                    // MethodHandle
                default -> offset += 5;                    // Integer, Float, refs, NameAndType, (Invoke)Dynamic
            }
        }
        return (u2(b, offset) & Opcodes.ACC_INTERFACE) != 0;
    }

    private static int u2(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    @Override
    public void close() throws IOException {
        for (JarFile jar : jars) {
            jar.close();
        }
    }
}
