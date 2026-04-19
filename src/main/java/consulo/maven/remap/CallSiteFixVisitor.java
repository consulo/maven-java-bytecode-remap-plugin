package consulo.maven.remap;

import org.objectweb.asm.*;

/**
 * Fixes call sites after remapping:
 * - Field type overrides (visitFieldInsn descriptor fix)
 * - Class-to-interface transitions (invokestatic on class → invokestatic on interface)
 *
 * Must run AFTER ClassRemapper (operates on remapped names).
 */
public class CallSiteFixVisitor extends ClassVisitor {
    private final MappingConfig config;
    private final ConfigBasedRemapper remapper;

    public CallSiteFixVisitor(ClassVisitor delegate, MappingConfig config, ConfigBasedRemapper remapper) {
        super(Opcodes.ASM9, delegate);
        this.config = config;
        this.remapper = remapper;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (mv != null) {
            mv = new CallSiteFixMethodVisitor(mv);
        }
        return mv;
    }

    private class CallSiteFixMethodVisitor extends MethodVisitor {
        CallSiteFixMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // Fix field type overrides
            String override = config.getFieldTypeOverride(owner, name);
            if (override != null) {
                descriptor = "L" + remapper.map(override) + ";";
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // Fix class-to-interface transitions
            if (config.isClassToInterface(owner)) {
                isInterface = true;
                // Fix opcode: invokevirtual → invokeinterface, invokestatic stays invokestatic
                if (opcode == Opcodes.INVOKEVIRTUAL) {
                    opcode = Opcodes.INVOKEINTERFACE;
                }
                // invokestatic on interface is valid as-is (Java 8+ static interface methods)
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
