package consulo.maven.remap;

import org.objectweb.asm.commons.Remapper;

/**
 * ASM Remapper that uses our MappingConfig for per-class, per-package,
 * and per-method remapping.
 */
public class ConfigBasedRemapper extends Remapper {
    private final MappingConfig config;

    public ConfigBasedRemapper(MappingConfig config) {
        this.config = config;
    }

    @Override
    public String map(String internalName) {
        return config.remapClass(internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        // Use the already-remapped owner name for lookup
        String remappedOwner = map(owner);
        return config.remapMethodName(remappedOwner, name);
    }
}
