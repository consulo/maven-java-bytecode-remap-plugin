package consulo.maven.javaBytecodeRemap;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.*;

/**
 * Fixes call sites after remapping:
 * - Field type overrides (visitFieldInsn descriptor fix)
 * - Class-to-interface transitions (invokestatic on class → invokestatic on interface)
 * - Owner kind changes found on the classpath: invokevirtual on an interface → invokeinterface, and back
 * - Call redirects: calls and static field reads rewritten to static helpers
 * - Instantiation redirects: {@code new Owner(...)} becomes {@code new Target(...)} (same constructor descriptor)
 * - instanceof redirects: {@code instanceof T} becomes a static boolean check
 * - constructors without their first argument: {@code new T(a, b)} / {@code super(a, b)} becomes {@code T(b)}
 *
 * Must run AFTER ClassRemapper (operates on remapped names).
 */
public class CallSiteFixVisitor extends ClassVisitor {
    private final MappingConfig config;
    private final ConfigBasedRemapper remapper;
    private final ClassKindIndex kinds;

    public CallSiteFixVisitor(ClassVisitor delegate, MappingConfig config, ConfigBasedRemapper remapper) {
        this(delegate, config, remapper, null);
    }

    public CallSiteFixVisitor(ClassVisitor delegate, MappingConfig config, ConfigBasedRemapper remapper, ClassKindIndex kinds) {
        super(Opcodes.ASM9, delegate);
        this.config = config;
        this.remapper = remapper;
        this.kinds = kinds;
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
        /** Redirected NEWs whose constructor call has not been seen yet, per owner (innermost last). */
        private final Map<String, Integer> pendingNews = new HashMap<>();

        CallSiteFixMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.INSTANCEOF) {
                String[] check = config.findInstanceofRedirect(type);
                if (check != null) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, check[0], check[1], "(Ljava/lang/Object;)Z", false);
                    return;
                }
            }
            if (opcode == Opcodes.NEW) {
                String target = config.findNewRedirect(type);
                if (target != null) {
                    pendingNews.merge(type, 1, Integer::sum);
                    super.visitTypeInsn(opcode, target);
                    return;
                }
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC) {
                MappingConfig.Redirect redirect = config.findFieldRedirect(owner, name);
                if (redirect != null) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, redirect.target(), redirect.targetName(), "()" + descriptor, false);
                    return;
                }
            }
            // Fix field type overrides
            String override = config.getFieldTypeOverride(owner, name);
            if (override != null) {
                descriptor = "L" + remapper.map(override) + ";";
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // only constructor calls that belong to a redirected NEW (not super() calls of subclasses)
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name) && pendingNews.getOrDefault(owner, 0) > 0) {
                pendingNews.merge(owner, -1, Integer::sum);
                super.visitMethodInsn(opcode, config.findNewRedirect(owner), name, descriptor, false);
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name) && config.dropsFirstConstructorArgument(owner, descriptor)) {
                Type[] arguments = Type.getArgumentTypes(descriptor);
                super.visitInsn(Opcodes.SWAP);
                super.visitInsn(Opcodes.POP);
                super.visitMethodInsn(opcode, owner, name, Type.getMethodDescriptor(Type.VOID_TYPE, arguments[1]), isInterface);
                return;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKESTATIC) {
                MappingConfig.Redirect redirect = config.findMethodRedirect(owner, name, descriptor, kinds);
                if (redirect != null) {
                    String desc = redirect.targetDesc() != null ? redirect.targetDesc()
                        : opcode == Opcodes.INVOKESTATIC
                        ? descriptor
                        : "(L" + redirect.owner() + ";" + descriptor.substring(1);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, redirect.target(), redirect.targetName(), desc, false);
                    return;
                }
            }
            // Fix class-to-interface transitions
            if (config.isClassToInterface(owner)) {
                isInterface = true;
                // Fix opcode: invokevirtual → invokeinterface, invokestatic stays invokestatic
                if (opcode == Opcodes.INVOKEVIRTUAL) {
                    opcode = Opcodes.INVOKEINTERFACE;
                }
                // invokestatic on interface is valid as-is (Java 8+ static interface methods)
            }
            else if (kinds != null && opcode != Opcodes.INVOKESPECIAL && !owner.startsWith("[")) {
                Boolean ownerIsInterface = kinds.isInterface(owner);
                if (ownerIsInterface != null && ownerIsInterface != isInterface) {
                    isInterface = ownerIsInterface;
                    if (opcode == Opcodes.INVOKEVIRTUAL && ownerIsInterface) {
                        opcode = Opcodes.INVOKEINTERFACE;
                    }
                    else if (opcode == Opcodes.INVOKEINTERFACE && !ownerIsInterface) {
                        opcode = Opcodes.INVOKEVIRTUAL;
                    }
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
