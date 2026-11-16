package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds bridge methods to classes of a type whose method another API declares with another result type, e.g.
 * {@code LocalizeValue getName()} of a quick fix that declares IntelliJ's {@code String getName()}: the bridge calls the first
 * source method the class declares and converts the result with a static adapter.
 * Runs AFTER ClassRemapper (operates on remapped names).
 */
public class BridgeVisitor extends ClassVisitor {
    private final List<MappingConfig.Bridge> bridges;
    private final ClassKindIndex kinds;
    private final Set<String> declared = new HashSet<>();
    private String className;
    private boolean isInterface;

    public BridgeVisitor(ClassVisitor delegate, List<MappingConfig.Bridge> bridges, ClassKindIndex kinds) {
        super(Opcodes.ASM9, delegate);
        this.bridges = bridges;
        this.kinds = kinds;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        className = name;
        isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        declared.add(name + descriptor);
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        if (!isInterface && kinds != null) {
            for (MappingConfig.Bridge bridge : bridges) {
                if (declared.contains(bridge.name() + bridge.desc()) || !Boolean.TRUE.equals(kinds.isSubtype(className, bridge.owner()))
                    || bridge.except().stream().anyMatch(e -> Boolean.TRUE.equals(kinds.isSubtype(className, e)))) {
                    continue;
                }
                for (String[] source : bridge.sources()) {
                    if (declared.contains(source[0] + source[1])) {
                        addBridge(bridge, source[0], source[1]);
                        declared.add(bridge.name() + bridge.desc());
                        break;
                    }
                }
            }
        }
        super.visitEnd();
    }

    private void addBridge(MappingConfig.Bridge bridge, String sourceName, String sourceDesc) {
        MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, bridge.name(), bridge.desc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(bridge.desc())) {
            mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, sourceName, sourceDesc, false);
        Type sourceReturn = Type.getReturnType(sourceDesc);
        Type bridgeReturn = Type.getReturnType(bridge.desc());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, bridge.adapter(), bridge.adapterName(),
            Type.getMethodDescriptor(bridgeReturn, sourceReturn), false);
        mv.visitInsn(bridgeReturn.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(slot + 1, slot);
        mv.visitEnd();
    }
}
