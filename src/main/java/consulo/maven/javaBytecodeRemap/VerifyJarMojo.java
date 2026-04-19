package consulo.maven.javaBytecodeRemap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.*;
import java.util.*;
import java.util.jar.*;

/**
 * Verifies every class in a target JAR using ASM's {@link CheckClassAdapter}.
 * Resolves types against the project's test classpath so the type-aware
 * {@code SimpleVerifier} can flag descriptor mismatches, bad stack maps, and
 * missing super methods produced by an earlier remap pass.
 *
 * All errors are collected and reported at the end; the build fails if any
 * class produced output.
 */
@Mojo(name = "verify-jar",
    defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.TEST)
public class VerifyJarMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /**
     * Artifact to verify, in {@code groupId:artifactId} form. Must be present in the project's
     * resolved dependencies.
     */
    @Parameter(required = true)
    private String verifyArtifact;

    /**
     * When true, log failures as warnings and continue the build instead of failing it.
     * Useful while iterating on a mapping: the full report is still printed.
     */
    @Parameter(defaultValue = "false")
    private boolean warnOnly;

    @Override
    public void execute() throws MojoExecutionException {
        File targetJar = findArtifactJar(verifyArtifact);
        getLog().info("Verifying " + targetJar);

        ClassLoader loader = buildClassLoader();

        List<Failure> failures = new ArrayList<>();
        int classesChecked = 0;

        try (JarFile jar = new JarFile(targetJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.equals("module-info.class")) {
                    continue;
                }

                byte[] bytes;
                try (InputStream is = jar.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }

                classesChecked++;
                StringBuilder combined = new StringBuilder();

                String verifyReport = verifyOne(bytes, loader);
                if (verifyReport != null) {
                    combined.append("[bytecode verifier]\n").append(verifyReport);
                }

                String overrideReport = checkOverrides(bytes, loader);
                if (overrideReport != null) {
                    if (combined.length() > 0) combined.append('\n');
                    combined.append("[override check]\n").append(overrideReport);
                }

                if (combined.length() > 0) {
                    failures.add(new Failure(name, combined.toString()));
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + targetJar, e);
        }

        getLog().info("Verified " + classesChecked + " classes; " + failures.size() + " failed");

        if (!failures.isEmpty()) {
            for (Failure f : failures) {
                if (warnOnly) {
                    getLog().warn("---- " + f.className + " ----");
                    for (String line : f.report.split("\\r?\\n")) {
                        getLog().warn("  " + line);
                    }
                } else {
                    getLog().error("---- " + f.className + " ----");
                    for (String line : f.report.split("\\r?\\n")) {
                        getLog().error("  " + line);
                    }
                }
            }
            String summary = "Bytecode verification failed for " + failures.size() + " classes (see log above)";
            if (warnOnly) {
                getLog().warn(summary + " — warnOnly=true, build continues");
            } else {
                throw new MojoExecutionException(summary);
            }
        }
    }

    /**
     * Returns null when the class verifies clean, or a report string when it doesn't.
     */
    private static String verifyOne(byte[] bytes, ClassLoader loader) {
        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);
        try {
            ClassReader cr = new ClassReader(bytes);
            CheckClassAdapter.verify(cr, loader, false, pw);
        } catch (Throwable t) {
            pw.println("Verifier threw: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(pw);
        }
        pw.flush();
        String text = out.toString();
        return text.isBlank() ? null : text;
    }

    /**
     * Find methods declared in the class that look like overrides but whose
     * (name + exact descriptor) does not match any non-private, non-static
     * method in a supertype. Descriptors must match exactly — params and return.
     *
     * Covariant returns are handled: javac emits a bridge method with the parent
     * descriptor alongside the covariant method. We treat all methods of a given
     * name in the subclass (including bridges) as the class's full "override
     * surface" — if any of them match a supertype descriptor, no mismatch.
     */
    private static String checkOverrides(byte[] bytes, ClassLoader loader) {
        ClassReader cr = new ClassReader(bytes);
        int access = cr.getAccess();
        if ((access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) {
            return null;
        }

        String className = cr.getClassName().replace('/', '.');

        Class<?> cls;
        try {
            cls = Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }

        Method[] declared;
        try {
            declared = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return null;
        }

        // All descriptors this class exposes, per method name — bridges included.
        Map<String, Set<String>> myDescsByName = new HashMap<>();
        for (Method m : declared) {
            int mods = m.getModifiers();
            if (Modifier.isPrivate(mods) || Modifier.isStatic(mods)) continue;
            String name = m.getName();
            if (name.equals("<init>") || name.equals("<clinit>")) continue;
            myDescsByName.computeIfAbsent(name, k -> new HashSet<>()).add(methodDescriptor(m));
        }

        List<String> mismatches = new ArrayList<>();
        Set<String> reportedSigs = new HashSet<>();

        for (Method m : declared) {
            int mods = m.getModifiers();
            if (Modifier.isPrivate(mods) || Modifier.isStatic(mods)) continue;
            if (m.isBridge() || m.isSynthetic()) continue;
            String name = m.getName();
            if (name.equals("<init>") || name.equals("<clinit>")) continue;

            String desc = methodDescriptor(m);
            String sigKey = name + desc;
            if (!reportedSigs.add(sigKey)) continue;

            // Gather all descriptors of same-named non-private instance methods in any supertype.
            SuperLookup sup = gatherSuperDescriptors(cls, name);
            if (sup.descriptors.isEmpty()) continue;  // not an override attempt

            Set<String> mine = myDescsByName.getOrDefault(name, Set.of());
            boolean anyExact = false;
            for (String d : sup.descriptors) {
                if (mine.contains(d)) { anyExact = true; break; }
            }
            if (anyExact) continue;

            // Pick one supertype descriptor to show in the report.
            String exampleSupDesc = sup.descriptors.iterator().next();
            String exampleOwner = sup.ownerFor.getOrDefault(exampleSupDesc, "?");
            mismatches.add(String.format(
                "%s%s shadows %s.%s%s — same name, different descriptor (not an override)",
                name, desc, exampleOwner, name, exampleSupDesc));
        }

        return mismatches.isEmpty() ? null : String.join("\n", mismatches);
    }

    private static SuperLookup gatherSuperDescriptors(Class<?> cls, String name) {
        SuperLookup out = new SuperLookup();
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        if (cls.getSuperclass() != null) todo.push(cls.getSuperclass());
        for (Class<?> i : cls.getInterfaces()) todo.push(i);

        while (!todo.isEmpty()) {
            Class<?> t = todo.pop();
            if (!seen.add(t)) continue;
            try {
                for (Method m : t.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    int mods = m.getModifiers();
                    if (Modifier.isPrivate(mods) || Modifier.isStatic(mods)) continue;
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String d = methodDescriptor(m);
                    out.descriptors.add(d);
                    out.ownerFor.putIfAbsent(d, t.getName());
                }
            } catch (Throwable ignore) {
            }
            if (t.getSuperclass() != null) todo.push(t.getSuperclass());
            for (Class<?> i : t.getInterfaces()) todo.push(i);
        }
        return out;
    }

    private static final class SuperLookup {
        final Set<String> descriptors = new LinkedHashSet<>();
        final Map<String, String> ownerFor = new HashMap<>();
    }

    private static String methodDescriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(typeDescriptor(p));
        }
        sb.append(")").append(typeDescriptor(m.getReturnType()));
        return sb.toString();
    }

    private static String typeDescriptor(Class<?> c) {
        if (c.isPrimitive()) {
            if (c == void.class) return "V";
            if (c == int.class) return "I";
            if (c == long.class) return "J";
            if (c == boolean.class) return "Z";
            if (c == byte.class) return "B";
            if (c == char.class) return "C";
            if (c == short.class) return "S";
            if (c == float.class) return "F";
            if (c == double.class) return "D";
        }
        if (c.isArray()) {
            return "[" + typeDescriptor(c.getComponentType());
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private File findArtifactJar(String ga) throws MojoExecutionException {
        String[] parts = ga.split(":");
        if (parts.length != 2) {
            throw new MojoExecutionException(
                "verifyArtifact must be in groupId:artifactId form, got: " + ga);
        }
        String group = parts[0];
        String artifact = parts[1];
        for (Object obj : project.getArtifacts()) {
            Artifact a = (Artifact) obj;
            if (a.getGroupId().equals(group) && a.getArtifactId().equals(artifact)) {
                File f = a.getFile();
                if (f == null || !f.exists()) {
                    throw new MojoExecutionException(
                        "Artifact " + ga + " resolved but has no file on disk");
                }
                return f;
            }
        }
        throw new MojoExecutionException(
            "Artifact " + ga + " not found in project dependencies");
    }

    private ClassLoader buildClassLoader() throws MojoExecutionException {
        List<URL> urls = new ArrayList<>();
        try {
            for (Object element : project.getTestClasspathElements()) {
                urls.add(new File((String) element).toURI().toURL());
            }
            for (Object obj : project.getArtifacts()) {
                Artifact a = (Artifact) obj;
                File f = a.getFile();
                if (f != null && f.exists()) {
                    urls.add(f.toURI().toURL());
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to build verifier classpath", e);
        }
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    private record Failure(String className, String report) {}
}
