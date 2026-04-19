package consulo.maven.remap;

import org.objectweb.asm.ClassWriter;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * ClassWriter that resolves class hierarchy from JAR files for COMPUTE_FRAMES.
 */
public class JarClassWriter extends ClassWriter {
    private final Map<String, byte[]> classBytes;

    public JarClassWriter(int flags, Map<String, byte[]> classBytes) {
        super(flags);
        this.classBytes = classBytes;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        // Try to find in our class data
        try {
            Set<String> supers1 = getSuperClasses(type1);
            Set<String> supers2 = getSuperClasses(type2);

            // Find common ancestor
            for (String s : supers1) {
                if (supers2.contains(s)) return s;
            }
        } catch (Exception ignored) {
        }

        return "java/lang/Object";
    }

    private Set<String> getSuperClasses(String type) {
        Set<String> result = new LinkedHashSet<>();
        result.add(type);
        String current = type;
        while (current != null && !"java/lang/Object".equals(current)) {
            result.add(current);
            byte[] data = classBytes.get(current);
            if (data != null) {
                try {
                    org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(data);
                    current = cr.getSuperName();
                } catch (Exception e) {
                    break;
                }
            } else {
                // Try system classloader
                try {
                    Class<?> cls = Class.forName(current.replace('/', '.'), false, getClass().getClassLoader());
                    Class<?> sup = cls.getSuperclass();
                    current = sup != null ? sup.getName().replace('.', '/') : null;
                } catch (ClassNotFoundException e) {
                    break;
                }
            }
        }
        result.add("java/lang/Object");
        return result;
    }
}
