package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.commons.Remapper;

/**
 * ASM Remapper that uses our MappingConfig for per-class, per-package,
 * and per-method remapping.
 */
public class ConfigBasedRemapper extends Remapper {
    private final MappingConfig config;
    private ClassKindIndex kinds;

    public ConfigBasedRemapper(MappingConfig config) {
        this.config = config;
    }

    /** Enables renames of methods declared or called through subtypes of a rename rule's owner. */
    public void setKinds(ClassKindIndex kinds) {
        this.kinds = kinds;
    }

    @Override
    public String map(String internalName) {
        return config.remapClass(internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        // Use the already-remapped owner name for lookup
        String remappedOwner = map(owner);
        String remappedDesc = mapMethodDesc(descriptor);
        String renamed = config.remapMethodName(remappedOwner, name, remappedDesc);
        if (!renamed.equals(name) || kinds == null) {
            return renamed;
        }
        // an override (or a call through a subtype) of a renamed method: e.g. getHashCode of a KeyDescriptor implementation
        for (java.util.Map.Entry<String, String> rule : config.methodRenamesByName(name, remappedDesc).entrySet()) {
            if (Boolean.TRUE.equals(kinds.isSubtype(remappedOwner, rule.getKey()))) {
                return rule.getValue();
            }
        }
        return name;
    }
}
