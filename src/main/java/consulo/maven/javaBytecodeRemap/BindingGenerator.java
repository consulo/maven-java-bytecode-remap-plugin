package consulo.maven.javaBytecodeRemap;

import org.objectweb.asm.*;

/**
 * Generates a _Binding class that implements consulo.component.bind.InjectingBinding.
 * This is the equivalent of what Consulo's annotation processor generates at compile time.
 */
public class BindingGenerator {

    /**
     * Generate a _Binding class for a given implementation.
     *
     * @param implClass     internal name of the implementation (e.g., org/jetbrains/kotlin/parsing/KotlinParserDefinition)
     * @param apiClass      internal name of the API interface (e.g., consulo/language/parser/ParserDefinition)
     * @param scope         component scope (e.g., APPLICATION, PROJECT)
     * @param type          extension (default), service or topic: the component annotation of the API
     * @param constructor   descriptor of the implementation constructor; its parameters are injected
     * @return bytecode of the generated _Binding class
     */
    public static byte[] generate(String implClass, String apiClass, String scope) {
        return generate(implClass + "_Binding", implClass, apiClass, scope, null, "()V");
    }

    /**
     * The constructor a binding uses: the public no-arg one, else the public one with the fewest parameters.
     */
    public static String chooseConstructor(byte[] classBytes) {
        String[] best = {null};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ("<init>".equals(name) && (access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                    if (best[0] == null || Type.getArgumentTypes(descriptor).length < Type.getArgumentTypes(best[0]).length) {
                        best[0] = descriptor;
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return best[0] != null ? best[0] : "()V";
    }

    public static byte[] generate(String bindingClass, String implClass, String apiClass, String scope, String type, String constructor) {
        Type[] parameters = Type.getArgumentTypes(constructor);
        String annotationClass = switch (type == null ? "extension" : type) {
            case "service" -> "consulo/annotation/component/ServiceAPI";
            case "topic" -> "consulo/annotation/component/TopicAPI";
            default -> "consulo/annotation/component/ExtensionAPI";
        };
        String scopeField = scope != null ? scope : "APPLICATION";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            bindingClass, null, "java/lang/Object",
            new String[]{"consulo/component/bind/InjectingBinding"});

        // Static field with the constructor parameter types
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "EMPTY_TYPES", "[Ljava/lang/reflect/Type;", null, null).visitEnd();

        // Static initializer
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(parameters.length);
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/reflect/Type");
        for (int i = 0; i < parameters.length; i++) {
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitLdcInsn(i);
            clinit.visitLdcInsn(parameters[i]);
            clinit.visitInsn(Opcodes.AASTORE);
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, bindingClass, "EMPTY_TYPES", "[Ljava/lang/reflect/Type;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(4, 0);
        clinit.visitEnd();

        // Constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // getApiClass
        addClassReturn(cw, "getApiClass", apiClass);

        // getApiClassName
        MethodVisitor apiName = cw.visitMethod(Opcodes.ACC_PUBLIC, "getApiClassName", "()Ljava/lang/String;", null, null);
        apiName.visitCode();
        apiName.visitLdcInsn(apiClass.replace('/', '.'));
        apiName.visitInsn(Opcodes.ARETURN);
        apiName.visitMaxs(1, 1);
        apiName.visitEnd();

        // getImplClass
        addClassReturn(cw, "getImplClass", implClass);

        // getComponentAnnotationClass
        addClassReturn(cw, "getComponentAnnotationClass", annotationClass);

        // getComponentScope
        MethodVisitor scopeM = cw.visitMethod(Opcodes.ACC_PUBLIC, "getComponentScope",
            "()Lconsulo/annotation/component/ComponentScope;", null, null);
        scopeM.visitCode();
        scopeM.visitFieldInsn(Opcodes.GETSTATIC, "consulo/annotation/component/ComponentScope",
            scopeField, "Lconsulo/annotation/component/ComponentScope;");
        scopeM.visitInsn(Opcodes.ARETURN);
        scopeM.visitMaxs(1, 1);
        scopeM.visitEnd();

        // getComponentProfiles
        addIntReturn(cw, "getComponentProfiles", 0);

        // getParametersCount
        addIntReturn(cw, "getParametersCount", parameters.length);

        // getParameterTypes
        MethodVisitor paramTypes = cw.visitMethod(Opcodes.ACC_PUBLIC, "getParameterTypes",
            "()[Ljava/lang/reflect/Type;", null, null);
        paramTypes.visitCode();
        paramTypes.visitFieldInsn(Opcodes.GETSTATIC, bindingClass, "EMPTY_TYPES", "[Ljava/lang/reflect/Type;");
        paramTypes.visitInsn(Opcodes.ARETURN);
        paramTypes.visitMaxs(1, 1);
        paramTypes.visitEnd();

        // create
        MethodVisitor create = cw.visitMethod(Opcodes.ACC_PUBLIC, "create",
            "([Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, implClass);
        create.visitInsn(Opcodes.DUP);
        for (int i = 0; i < parameters.length; i++) {
            create.visitVarInsn(Opcodes.ALOAD, 1);
            create.visitLdcInsn(i);
            create.visitInsn(Opcodes.AALOAD);
            create.visitTypeInsn(Opcodes.CHECKCAST, parameters[i].getInternalName());
        }
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, implClass, "<init>", constructor, false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(2 + parameters.length + 2, 2);
        create.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addClassReturn(ClassWriter cw, String methodName, String className) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()Ljava/lang/Class;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void addIntReturn(ClassWriter cw, String methodName, int value) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        mv.visitCode();
        if (value == 0) {
            mv.visitInsn(Opcodes.ICONST_0);
        } else {
            mv.visitLdcInsn(value);
        }
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }
}
