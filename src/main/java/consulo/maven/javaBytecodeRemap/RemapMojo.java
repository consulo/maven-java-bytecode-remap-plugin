package consulo.maven.javaBytecodeRemap;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Aggregates classes from project dependencies, remaps all references,
 * and writes the result to target/classes so maven-jar-plugin packages it normally.
 */
@Mojo(name = "remap", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class RemapMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(required = true)
    private File mappingFile;

    @Parameter
    private String includeGroupIds;

    @Parameter
    private String[] excludeResources;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            File classesDir = new File(project.getBuild().getOutputDirectory());

            List<File> inputJars = collectInputJars();
            if (inputJars.isEmpty()) {
                getLog().info("No matching dependency JARs found, skipping remap");
                return;
            }

            if (isUpToDate(classesDir, inputJars)) {
                getLog().info("Remap is up-to-date, skipping");
                return;
            }

            getLog().info("Loading mapping from: " + mappingFile);
            MappingConfig config = MappingConfig.parse(mappingFile);
            getLog().info("  Package rules: " + config.getPackageRules().size());
            getLog().info("  Class rules: " + config.getClassRules().size());

            ConfigBasedRemapper remapper = new ConfigBasedRemapper(config);

            classesDir.mkdirs();

            int classCount = 0;
            int resourceCount = 0;
            int skippedCount = 0;
            Set<String> addedEntries = new HashSet<>();

            for (File inputJar : inputJars) {
                getLog().info("  Including: " + inputJar.getName());

                try (JarFile jar = new JarFile(inputJar)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();

                        if (name.startsWith("META-INF/")) continue;
                        if (entry.isDirectory()) continue;
                        if (shouldExclude(name)) { skippedCount++; continue; }

                        if (name.endsWith(".class")) {
                            byte[] classBytes = jar.getInputStream(entry).readAllBytes();
                            byte[] remapped = remapClass(classBytes, config, remapper);
                            String newName = remapResourcePath(name, config);

                            if (addedEntries.add(newName)) {
                                File outFile = new File(classesDir, newName.replace('/', File.separatorChar));
                                outFile.getParentFile().mkdirs();
                                Files.write(outFile.toPath(), remapped);
                                classCount++;
                            }
                        } else {
                            if (addedEntries.add(name)) {
                                File outFile = new File(classesDir, name.replace('/', File.separatorChar));
                                outFile.getParentFile().mkdirs();
                                try (InputStream is = jar.getInputStream(entry)) {
                                    Files.copy(is, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                }
                                resourceCount++;
                            }
                        }
                    }
                }
            }

            writeCacheMarker();

            getLog().info("Remap complete: " + classCount + " classes, "
                          + resourceCount + " resources, " + skippedCount + " excluded → target/classes");

        } catch (Exception e) {
            throw new MojoExecutionException("Remap failed", e);
        }
    }

    private List<File> collectInputJars() {
        Set<String> groupIds = null;
        if (includeGroupIds != null && !includeGroupIds.isEmpty()) {
            groupIds = new HashSet<>(Arrays.asList(includeGroupIds.split(",")));
        }

        List<File> jars = new ArrayList<>();
        for (Object obj : project.getArtifacts()) {
            org.apache.maven.artifact.Artifact artifact = (org.apache.maven.artifact.Artifact) obj;
            if (!"compile".equals(artifact.getScope()) && !"runtime".equals(artifact.getScope())) {
                continue;
            }
            if (groupIds != null && !groupIds.contains(artifact.getGroupId())) {
                continue;
            }
            File file = artifact.getFile();
            if (file != null && file.exists() && file.getName().endsWith(".jar")) {
                jars.add(file);
            }
        }
        return jars;
    }

    private boolean isUpToDate(File classesDir, List<File> inputJars) {
        File cacheMarker = getCacheMarkerFile();
        if (!cacheMarker.exists()) return false;
        long cacheTime = cacheMarker.lastModified();
        if (mappingFile.lastModified() > cacheTime) return false;
        for (File jar : inputJars) {
            if (jar.lastModified() > cacheTime) return false;
        }
        return true;
    }

    private File getCacheMarkerFile() {
        return new File(project.getBuild().getDirectory(), "consulo-remap.cache");
    }

    private void writeCacheMarker() throws IOException {
        File marker = getCacheMarkerFile();
        marker.getParentFile().mkdirs();
        new FileOutputStream(marker).close();
    }

    private byte[] remapClass(byte[] classBytes, MappingConfig config, ConfigBasedRemapper remapper) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        String originalName = reader.getClassName();

        ClassVisitor visitor = writer;
        visitor = new ClassRemapper(visitor, remapper);

        MappingConfig.ClassModification mod = config.getModification(originalName);
        if (mod != null) {
            visitor = new ClassModifierVisitor(visitor, mod, remapper);
        }

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private String remapResourcePath(String path, MappingConfig config) {
        if (!path.endsWith(".class")) return path;
        String className = path.substring(0, path.length() - 6);
        return config.remapClass(className) + ".class";
    }

    private boolean shouldExclude(String name) {
        if (excludeResources == null) return false;
        for (String pattern : excludeResources) {
            if (pattern.endsWith("/**")) {
                String prefix = pattern.substring(0, pattern.length() - 3);
                if (name.startsWith(prefix)) return true;
            } else if (name.equals(pattern)) {
                return true;
            }
        }
        return false;
    }
}
