package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the {@code consulo.component.bind.TopicBinding} of a topic (listener) interface, as Consulo's annotation
 * processor does for its own {@code @TopicAPI} interfaces; without one, the message bus calls listeners by reflection
 * and logs an error. Two classes: {@code Api_Binding} lists the methods, {@code Api_Binding$Invoker} calls them
 * ({@code BiConsumer<listener, Object[] arguments>}, dispatching by method index).
 */
public class TopicBindingGenerator {
    private static final String TOPIC_METHOD = "consulo/component/bind/TopicMethod";
    private static final String BI_CONSUMER = "java/util/function/BiConsumer";

    /** @return binding class name → bytecode (the binding and its invoker) */
    public static Map<String, byte[]> generate(String apiClass, byte[] apiBytes) {
        List<String[]> methods = new ArrayList<>(); // name, descriptor
        new ClassReader(apiBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_ABSTRACT) != 0 && (access & Opcodes.ACC_STATIC) == 0) {
                    methods.add(new String[]{name, descriptor});
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);

        String bindingClass = apiClass + "_Binding";
        String invokerClass = bindingClass + "$Invoker";
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put(bindingClass, binding(apiClass, bindingClass, invokerClass, methods));
        result.put(invokerClass, invoker(apiClass, invokerClass, methods));
        return result;
    }

    private static byte[] binding(String apiClass, String bindingClass, String invokerClass, List<String[]> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, bindingClass, null, "java/lang/Object",
            new String[]{"consulo/component/bind/TopicBinding"});

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor name = cw.visitMethod(Opcodes.ACC_PUBLIC, "getApiClassName", "()Ljava/lang/String;", null, null);
        name.visitCode();
        name.visitLdcInsn(apiClass.replace('/', '.'));
        name.visitInsn(Opcodes.ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "methods", "()[L" + TOPIC_METHOD + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn(methods.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, TOPIC_METHOD);
        for (int i = 0; i < methods.size(); i++) {
            Type[] arguments = Type.getArgumentTypes(methods.get(i)[1]);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(i);
            mv.visitLdcInsn(methods.get(i)[0]);
            mv.visitLdcInsn(arguments.length);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/reflect/Type");
            for (int a = 0; a < arguments.length; a++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(a);
                pushClass(mv, arguments[a]);
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitTypeInsn(Opcodes.NEW, invokerClass);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(i);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, invokerClass, "<init>", "(I)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOPIC_METHOD, "create",
                "(Ljava/lang/String;[Ljava/lang/reflect/Type;L" + BI_CONSUMER + ";)L" + TOPIC_METHOD + ";", false);
            mv.visitInsn(Opcodes.AASTORE);
        }
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] invoker(String apiClass, String invokerClass, List<String[]> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, invokerClass, null, "java/lang/Object",
            new String[]{BI_CONSUMER});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "index", "I", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, invokerClass, "index", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        Label[] cases = new Label[methods.size()];
        for (int i = 0; i < cases.length; i++) {
            cases[i] = new Label();
        }
        Label end = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, invokerClass, "index", "I");
        if (cases.length > 0) {
            mv.visitTableSwitchInsn(0, cases.length - 1, end, cases);
        }
        else {
            mv.visitInsn(Opcodes.POP);
        }
        for (int i = 0; i < cases.length; i++) {
            mv.visitLabel(cases[i]);
            String descriptor = methods.get(i)[1];
            Type[] arguments = Type.getArgumentTypes(descriptor);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, apiClass);
            for (int a = 0; a < arguments.length; a++) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
                mv.visitLdcInsn(a);
                mv.visitInsn(Opcodes.AALOAD);
                unbox(mv, arguments[a]);
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, apiClass, methods.get(i)[0], descriptor, true);
            Type returnType = Type.getReturnType(descriptor);
            if (returnType.getSize() == 2) {
                mv.visitInsn(Opcodes.POP2);
            }
            else if (returnType.getSize() == 1) {
                mv.visitInsn(Opcodes.POP);
            }
            mv.visitJumpInsn(Opcodes.GOTO, end);
        }
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void pushClass(MethodVisitor mv, Type type) {
        String box = box(type);
        if (box != null) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, box, "TYPE", "Ljava/lang/Class;");
        }
        else {
            mv.visitLdcInsn(type);
        }
    }

    private static void unbox(MethodVisitor mv, Type type) {
        String box = box(type);
        if (box == null) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, type.getInternalName());
            return;
        }
        mv.visitTypeInsn(Opcodes.CHECKCAST, box);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, box, type.getClassName() + "Value", "()" + type.getDescriptor(), false);
    }

    private static String box(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }
}
