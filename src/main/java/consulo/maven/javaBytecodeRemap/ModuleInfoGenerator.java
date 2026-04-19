package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates module-info.class from a ModuleDefinition.
 */
public class ModuleInfoGenerator {

    public static byte[] generate(MappingConfig.ModuleDefinition def) {
        ClassWriter cw = new ClassWriter(0);

        // Module class
        cw.visit(Opcodes.V21, Opcodes.ACC_MODULE, "module-info", null, null, null);

        ModuleVisitor mv = cw.visitModule(def.name, 0, null);

        // requires java.base (always)
        mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);

        // requires
        for (String req : def.requires) {
            mv.visitRequire(req, 0, null);
        }

        // exports
        for (String exp : def.exports) {
            mv.visitExport(exp, 0);
        }

        // provides
        for (MappingConfig.ServiceProvision sp : def.provides) {
            String[] impls = new String[sp.implementations.size() + sp.bindings.size()];
            int i = 0;
            for (String impl : sp.implementations) {
                impls[i++] = impl;
            }
            for (MappingConfig.BindingDeclaration bd : sp.bindings) {
                impls[i++] = bd.implClass + "_Binding";
            }
            mv.visitProvide(sp.interfaceName, impls);
        }

        mv.visitEnd();
        cw.visitEnd();

        return cw.toByteArray();
    }
}
