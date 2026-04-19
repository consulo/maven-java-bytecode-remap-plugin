package consulo.maven.remap;

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
     * @return bytecode of the generated _Binding class
     */
    public static byte[] generate(String implClass, String apiClass, String scope) {
        String bindingClass = implClass + "_Binding";
        String scopeField = scope != null ? scope : "APPLICATION";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            bindingClass, null, "java/lang/Object",
            new String[]{"consulo/component/bind/InjectingBinding"});

        // Static field for empty types array
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "EMPTY_TYPES", "[Ljava/lang/reflect/Type;", null, null).visitEnd();

        // Static initializer
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(Opcodes.ICONST_0);
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/reflect/Type");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, bindingClass, "EMPTY_TYPES", "[Ljava/lang/reflect/Type;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(1, 0);
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
        addClassReturn(cw, "getComponentAnnotationClass", "consulo/annotation/component/ExtensionAPI");

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
        addIntReturn(cw, "getParametersCount", 0);

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
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, implClass, "<init>", "()V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(2, 2);
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
