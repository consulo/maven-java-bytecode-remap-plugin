package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Kinds and supertypes of classes (by remapped internal name), looked up in the classes being remapped and in the
 * project's dependency JARs. A remap target can change the kind (an IntelliJ class that is an interface in Consulo),
 * so call sites are fixed by the target's real kind; redirects for "any owner" apply only to subtypes of their owner.
 */
public class ClassKindIndex implements AutoCloseable {
    /** Access flags and direct supertypes (remapped names). */
    private record Header(int access, List<String> supertypes) {
    }

    private final Map<String, byte[]> ownClasses;
    private final Function<String, String> ownNameMapper;
    private final List<JarFile> jars = new ArrayList<>();
    private final Map<String, Optional<Header>> cache = new HashMap<>();
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
        Header header = header(internalName);
        return header == null ? null : (header.access() & Opcodes.ACC_INTERFACE) != 0;
    }

    /** Whether {@code type} is {@code ancestor} or extends/implements it; {@code null} if that is unknown (a missing class on the way). */
    public synchronized Boolean isSubtype(String type, String ancestor) {
        Deque<String> todo = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        boolean unknown = false;
        while (!todo.isEmpty()) {
            String t = todo.pop();
            if (t.equals(ancestor)) {
                return true;
            }
            if (!seen.add(t) || t.equals("java/lang/Object")) {
                continue;
            }
            Header header = header(t);
            if (header == null) {
                unknown |= !t.startsWith("java/");
                continue;
            }
            todo.addAll(header.supertypes());
        }
        return unknown ? null : false;
    }

    private Header header(String internalName) {
        return cache.computeIfAbsent(internalName, n -> Optional.ofNullable(lookup(n))).orElse(null);
    }

    private Header lookup(String internalName) {
        if (ownByRemappedName == null) {
            ownByRemappedName = new HashMap<>();
            ownClasses.forEach((name, bytes) -> ownByRemappedName.put(ownNameMapper.apply(name), bytes));
        }
        byte[] own = ownByRemappedName.get(internalName);
        if (own != null) {
            return parse(own, ownNameMapper);
        }
        for (JarFile jar : jars) {
            ZipEntry entry = jar.getEntry(internalName + ".class");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return parse(in.readAllBytes(), Function.identity());
                }
                catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Reads the header after the constant pool by hand, so any class file version works (ASM rejects newer ones). */
    private static Header parse(byte[] b, Function<String, String> nameMapper) {
        int count = u2(b, 8);
        int[] offsets = new int[count];
        int offset = 10;
        for (int i = 1; i < count; i++) {
            offsets[i] = offset;
            int tag = b[offset] & 0xFF;
            switch (tag) {
                case 1 -> offset += 3 + u2(b, offset + 1); // Utf8
                case 5, 6 -> { offset += 9; i++; }         // Long, Double take two slots
                case 7, 8, 16, 19, 20 -> offset += 3;      // Class, String, MethodType, Module, Package
                case 15 -> offset += 4;                    // MethodHandle
                default -> offset += 5;                    // Integer, Float, refs, NameAndType, (Invoke)Dynamic
            }
        }
        int access = u2(b, offset);
        List<String> supertypes = new ArrayList<>();
        int superIndex = u2(b, offset + 4);
        if (superIndex != 0) {
            supertypes.add(nameMapper.apply(className(b, offsets, superIndex)));
        }
        int interfaces = u2(b, offset + 6);
        for (int i = 0; i < interfaces; i++) {
            supertypes.add(nameMapper.apply(className(b, offsets, u2(b, offset + 8 + 2 * i))));
        }
        return new Header(access, supertypes);
    }

    private static String className(byte[] b, int[] offsets, int classIndex) {
        int utf8 = offsets[u2(b, offsets[classIndex] + 1)];
        return new String(b, utf8 + 3, u2(b, utf8 + 1), StandardCharsets.UTF_8); // class names have no modified-UTF-8 specials
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
