package consulo.maven.javaBytecodeRemap;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
 * Remaps a JAR file in-place using the mapping configuration.
 * Designed to run after maven-shade-plugin to apply per-class remapping
 * and class modifications.
 *
 * Usage:
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;remap-jar&lt;/id&gt;
 *   &lt;phase&gt;package&lt;/phase&gt;
 *   &lt;goals&gt;&lt;goal&gt;remap-jar&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;mappingFile&gt;remap.xml&lt;/mappingFile&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 */
@Mojo(name = "remap-jar", requiresDependencyResolution = ResolutionScope.COMPILE)
public class RemapJarMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(required = true)
    private File mappingFile;

    /**
     * More mapping files applied on top of {@link #mappingFile}, in order (e.g. a project's own rules over a
     * shared mapping). Later files win for the same class, method, redirect or module definition.
     */
    @Parameter
    private List<File> extraMappingFiles = new ArrayList<>();

    /**
     * JAR file to remap. Defaults to the project's main artifact JAR.
     */
    @Parameter
    private File inputJar;

    /**
     * Patterns for entries to exclude (e.g., org/jetbrains/annotations/**)
     */
    @Parameter
    private String[] excludeResources;

    /** Increment when plugin logic changes to invalidate cache */
    private static final int CACHE_VERSION = 14;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            File jar = inputJar;
            if (jar == null) {
                jar = project.getArtifact().getFile();
            }
            if (jar == null || !jar.exists()) {
                getLog().warn("No JAR to remap");
                return;
            }

            // Cache check with version
            File cacheMarker = new File(project.getBuild().getDirectory(), "consulo-remap-jar.v" + CACHE_VERSION + ".cache");
            if (cacheMarker.exists()
                && jar.lastModified() <= cacheMarker.lastModified()
                && mappingFile.lastModified() <= cacheMarker.lastModified()
                && extraMappingFiles.stream().allMatch(f -> f.lastModified() <= cacheMarker.lastModified())) {
                getLog().info("Remap-jar is up-to-date, skipping");
                return;
            }
            // Clean old cache versions
            for (File f : Objects.requireNonNullElse(
                    new File(project.getBuild().getDirectory()).listFiles((d, n) -> n.startsWith("consulo-remap-jar.v") && n.endsWith(".cache")),
                    new File[0])) {
                f.delete();
            }

            getLog().info("Loading mapping from: " + mappingFile);
            MappingConfig config = MappingConfig.parse(mappingFile);
            for (File extra : extraMappingFiles) {
                getLog().info("Loading extra mapping from: " + extra);
                config.mergeFrom(MappingConfig.parse(extra));
            }
            getLog().info("  Package rules: " + config.getPackageRules().size());
            getLog().info("  Class rules: " + config.getClassRules().size());

            ConfigBasedRemapper remapper = new ConfigBasedRemapper(config);

            // Process JAR: read → remap → write to temp → replace original
            File tempJar = new File(jar.getParent(), jar.getName() + ".remapping");

            int classCount = 0;
            int resourceCount = 0;
            int skippedCount = 0;

            // Pass 1: Read all class bytes for hierarchy resolution
            allClassBytes.clear();
            Map<String, byte[]> classEntries = new LinkedHashMap<>();
            Map<String, byte[]> resourceEntries = new LinkedHashMap<>();

            try (JarFile input = new JarFile(jar)) {
                Enumeration<JarEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (shouldExclude(name)) { skippedCount++; continue; }

                    byte[] data = input.getInputStream(entry).readAllBytes();
                    if (name.endsWith(".class")) {
                        classEntries.put(name, data);
                        String className = name.substring(0, name.length() - 6);
                        allClassBytes.put(className, data);
                    } else {
                        resourceEntries.put(name, data);
                    }
                }
            }

            // Kinds of call owners: the remapped classes and the dependency classpath (e.g. the platform)
            List<File> classpath = new ArrayList<>();
            for (Object obj : project.getArtifacts()) {
                File file = ((org.apache.maven.artifact.Artifact) obj).getFile();
                if (file != null) {
                    classpath.add(file);
                }
            }
            kinds = new ClassKindIndex(allClassBytes, config::remapClass, classpath);
            remapper.setKinds(kinds);

            // Pass 2: Remap and write
            Set<String> writtenEntries = new HashSet<>();
            Map<String, byte[]> remappedClasses = new HashMap<>();

            try (JarOutputStream jos = new JarOutputStream(
                     new BufferedOutputStream(new FileOutputStream(tempJar)))) {

                for (var entry : classEntries.entrySet()) {
                    boolean verbatim = config.isVerbatim(entry.getKey().substring(0, entry.getKey().length() - 6));
                    byte[] remapped = verbatim ? entry.getValue() : remapClass(entry.getValue(), config, remapper);
                    String newName = verbatim ? entry.getKey() : remapResourcePath(entry.getKey(), config);
                    remappedClasses.put(newName, remapped);
                    if (writtenEntries.add(newName)) {
                        jos.putNextEntry(new JarEntry(newName));
                        jos.write(remapped);
                        jos.closeEntry();
                        classCount++;
                    }
                }

                for (var entry : resourceEntries.entrySet()) {
                    if (writtenEntries.add(entry.getKey())) {
                        jos.putNextEntry(new JarEntry(entry.getKey()));
                        jos.write(entry.getValue());
                        jos.closeEntry();
                        resourceCount++;
                    }
                }

                // Generate module artifacts inside the JAR
                MappingConfig.ModuleDefinition moduleDef = config.getModuleDefinition();
                if (moduleDef != null) {
                    for (MappingConfig.ServiceProvision sp : moduleDef.provides) {
                        // Generate _Binding classes
                        for (MappingConfig.BindingDeclaration bd : sp.bindings) {
                            String bindingClass = bd.implClass + "_Binding";
                            String bindingEntry = bindingClass + ".class";
                            if (!writtenEntries.add(bindingEntry)) continue;
                            if ("topicBinding".equals(bd.type)) {
                                byte[] apiBytes = remappedClasses.get(bd.apiClass + ".class");
                                if (apiBytes == null) {
                                    throw new MojoExecutionException("Topic binding for a class not in the jar: " + bd.apiClass);
                                }
                                for (var generated : TopicBindingGenerator.generate(bd.apiClass, apiBytes).entrySet()) {
                                    String entryName = generated.getKey() + ".class";
                                    if (!entryName.equals(bindingEntry) && !writtenEntries.add(entryName)) continue;
                                    jos.putNextEntry(new JarEntry(entryName));
                                    jos.write(generated.getValue());
                                    jos.closeEntry();
                                }
                                getLog().info("  Generated topic binding: " + bindingClass.replace('/', '.'));
                                continue;
                            }
                            byte[] implBytes = remappedClasses.get(bd.implClass + ".class");
                            String constructor = implBytes != null ? BindingGenerator.chooseConstructor(implBytes) : "()V";
                            byte[] bindingBytes = BindingGenerator.generate(bd.implClass, bd.apiClass,
                                bd.scope != null ? bd.scope : "APPLICATION", bd.type, constructor);
                            jos.putNextEntry(new JarEntry(bindingEntry));
                            jos.write(bindingBytes);
                            jos.closeEntry();
                            getLog().info("  Generated binding: " + bindingClass.replace('/', '.'));
                        }

                        // Generate META-INF/services
                        StringBuilder sb = new StringBuilder();
                        for (String impl : sp.implementations) {
                            sb.append(impl.replace('/', '.')).append('\n');
                        }
                        for (MappingConfig.BindingDeclaration bd : sp.bindings) {
                            sb.append((bd.implClass + "_Binding").replace('/', '.')).append('\n');
                        }
                        String servicesPath = "META-INF/services/" + sp.interfaceName.replace('/', '.');
                        if (!writtenEntries.add(servicesPath)) continue;
                        jos.putNextEntry(new JarEntry(servicesPath));
                        jos.write(sb.toString().getBytes());
                        jos.closeEntry();
                        getLog().info("  Generated service: " + servicesPath);
                    }

                    // Generate module-info.class
                    if (writtenEntries.add("module-info.class")) {
                        Set<String> packages = new TreeSet<>();
                        for (String entry : writtenEntries) {
                            int slash = entry.lastIndexOf('/');
                            if (entry.endsWith(".class") && slash > 0 && !entry.startsWith("META-INF/")) {
                                packages.add(entry.substring(0, slash));
                            }
                        }
                        byte[] moduleInfo = ModuleInfoGenerator.generate(moduleDef, packages, msg -> getLog().warn(msg));
                        jos.putNextEntry(new JarEntry("module-info.class"));
                        jos.write(moduleInfo);
                        jos.closeEntry();
                        getLog().info("  Generated module-info for: " + moduleDef.name);
                    }
                }
            }

            kinds.close();

            // Replace original with remapped
            Files.move(tempJar.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Write cache
            cacheMarker.getParentFile().mkdirs();
            new FileOutputStream(cacheMarker).close();

            getLog().info("Remap-jar complete: " + classCount + " classes, "
                          + resourceCount + " resources, " + skippedCount + " excluded");

        } catch (Exception e) {
            throw new MojoExecutionException("Remap-jar failed", e);
        }
    }

    /** Interface or class, for call sites whose owner changed kind */
    private ClassKindIndex kinds;

    /** All class bytes for hierarchy resolution during frame computation */
    private Map<String, byte[]> allClassBytes = new HashMap<>();

    private byte[] remapClass(byte[] classBytes, MappingConfig config, ConfigBasedRemapper remapper) {
        // Use EXPAND_FRAMES to preserve existing frames, COMPUTE_MAXS to fix locals
        try {
            return doRemapClass(classBytes, config, remapper, ClassWriter.COMPUTE_MAXS, ClassReader.EXPAND_FRAMES);
        } catch (Exception e) {
            // Fallback for old bytecode
            return doRemapClass(classBytes, config, remapper, 0, 0);
        }
    }

    private byte[] doRemapClass(byte[] classBytes, MappingConfig config, ConfigBasedRemapper remapper,
                                int writerFlags, int readerFlags) {
        ClassReader reader = new ClassReader(classBytes);
        String originalName = reader.getClassName();

        MappingConfig.ClassModification mod = config.getModification(originalName);
        ClassWriter writer = new JarClassWriter(writerFlags, allClassBytes);

        // Pipeline: modifications → remapper → call site fix → writer
        ClassVisitor visitor = writer;

        // 3. Fix call sites (field descriptors, class-to-interface transitions)
        visitor = new CallSiteFixVisitor(visitor, config, remapper, kinds);

        // 2. Remap all class/method/field references
        visitor = new ClassRemapper(visitor, remapper);

        // 1. Apply modifications (runs first, on original bytecode)
        if (mod != null) {
            visitor = new ClassModifierVisitor(visitor, mod, remapper);
        }

        reader.accept(visitor, readerFlags);
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
