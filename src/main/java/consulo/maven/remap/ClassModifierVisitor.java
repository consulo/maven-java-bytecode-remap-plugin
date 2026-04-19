package consulo.maven.remap;

import org.objectweb.asm.*;

/**
 * ASM ClassVisitor that adds annotations, methods, and fields to a class
 * based on the modification configuration.
 */
public class ClassModifierVisitor extends ClassVisitor {
    private final MappingConfig.ClassModification mod;
    private final ConfigBasedRemapper remapper;
    private String className;
    private final java.util.Set<String> existingMethods = new java.util.HashSet<>();

    public ClassModifierVisitor(ClassVisitor delegate, MappingConfig.ClassModification mod, ConfigBasedRemapper remapper) {
        super(Opcodes.ASM9, delegate);
        this.mod = mod;
        this.remapper = remapper;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        String newType = mod.fieldTypeReplacements.get(name);
        if (newType != null) {
            String remappedType = remapper.map(newType);
            descriptor = "L" + remappedType + ";";
            signature = null;
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        existingMethods.add(name + descriptor);
        // Check if this method should be overridden
        MappingConfig.MethodOverride matched = null;
        for (MappingConfig.MethodOverride override : mod.methodOverrides) {
            if (override.name.equals(name)) {
                // Match by descriptor if specified, otherwise match by name only
                if (override.originalDesc != null && !override.originalDesc.equals(descriptor)) {
                    continue;
                }
                matched = override;
                break;
            }
        }

        if (matched != null) {
            // Modify access
            if (matched.changeAccess != null) {
                int newAccess = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
                newAccess |= switch (matched.changeAccess) {
                    case "public" -> Opcodes.ACC_PUBLIC;
                    case "protected" -> Opcodes.ACC_PROTECTED;
                    case "private" -> Opcodes.ACC_PRIVATE;
                    default -> 0;
                };
                access = newAccess;
            }

            // Modify descriptor if any param/return changes
            if (matched.replaceReturnType != null || !matched.replaceParams.isEmpty()
                || !matched.removeParams.isEmpty() || !matched.addParams.isEmpty()) {
                descriptor = buildNewDescriptor(descriptor, matched);
                signature = null; // clear generic signature since we changed the descriptor
            }

            // Replace the method body entirely: write the new body to the delegate
            // and return a no-op visitor that absorbs the original body events.
            if (matched.bodyInstructions != null && !matched.bodyInstructions.isEmpty()) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv != null) {
                    mv.visitCode();
                    for (MappingConfig.Instruction insn : matched.bodyInstructions) {
                        emitInstruction(mv, insn);
                    }
                    mv.visitMaxs(4, 4);
                    mv.visitEnd();
                }
                return NO_OP_METHOD_VISITOR;
            }
        }

        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    private static final MethodVisitor NO_OP_METHOD_VISITOR = new MethodVisitor(Opcodes.ASM9, null) {};

    private String buildNewDescriptor(String originalDesc, MappingConfig.MethodOverride override) {
        // Parse original params
        java.util.List<String> paramTypes = parseParamDescriptors(originalDesc);

        // Apply replaceParam
        for (var entry : override.replaceParams.entrySet()) {
            int idx = entry.getKey();
            if (idx >= 0 && idx < paramTypes.size()) {
                paramTypes.set(idx, toDescriptor(remapper.map(entry.getValue())));
            }
        }

        // Apply removeParam (reverse order to keep indices stable)
        var removeList = new java.util.ArrayList<>(override.removeParams);
        removeList.sort(java.util.Collections.reverseOrder());
        for (int idx : removeList) {
            if (idx >= 0 && idx < paramTypes.size()) {
                paramTypes.remove(idx);
            }
        }

        // Apply addParam
        for (var entry : override.addParams.entrySet()) {
            int idx = entry.getKey();
            String desc = toDescriptor(remapper.map(entry.getValue()));
            if (idx >= paramTypes.size()) {
                paramTypes.add(desc);
            } else {
                paramTypes.add(idx, desc);
            }
        }

        // Build new descriptor
        StringBuilder sb = new StringBuilder("(");
        for (String p : paramTypes) {
            sb.append(p);
        }
        sb.append(")");

        // Return type
        if (override.replaceReturnType != null) {
            sb.append(toDescriptor(remapper.map(override.replaceReturnType)));
        } else {
            int parenClose = originalDesc.indexOf(')');
            sb.append(originalDesc.substring(parenClose + 1));
        }

        return sb.toString();
    }

    /**
     * Parse parameter type descriptors from a method descriptor.
     * "(Ljava/lang/String;I)V" → ["Ljava/lang/String;", "I"]
     */
    private java.util.List<String> parseParamDescriptors(String descriptor) {
        java.util.List<String> params = new java.util.ArrayList<>();
        int i = 1; // skip '('
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            int start = i;
            if (descriptor.charAt(i) == 'L') {
                int semi = descriptor.indexOf(';', i);
                i = semi + 1;
            } else if (descriptor.charAt(i) == '[') {
                i++;
                if (descriptor.charAt(i) == 'L') {
                    int semi = descriptor.indexOf(';', i);
                    i = semi + 1;
                } else {
                    i++;
                }
            } else {
                i++; // primitive
            }
            params.add(descriptor.substring(start, i));
        }
        return params;
    }

    @Override
    public void visitEnd() {
        // Add annotations
        for (String annotation : mod.annotations) {
            String remapped = remapper.map(annotation);
            AnnotationVisitor av = super.visitAnnotation("L" + remapped + ";", true);
            if (av != null) {
                av.visitEnd();
            }
        }

        // Add fields
        for (MappingConfig.FieldToAdd field : mod.fields) {
            String remappedType = remapper.map(field.type);
            String descriptor = "L" + remappedType + ";";
            FieldVisitor fv = super.visitField(Opcodes.ACC_PUBLIC, field.name, descriptor, null, null);
            if (fv != null) {
                fv.visitEnd();
            }
        }

        // Add methods
        for (MappingConfig.MethodToAdd method : mod.methods) {
            addMethod(method);
        }

        super.visitEnd();
    }

    private void addMethod(MappingConfig.MethodToAdd method) {
        String remappedReturn = remapper.map(method.returnType);
        String returnDesc = toDescriptor(remappedReturn);

        StringBuilder paramDesc = new StringBuilder("(");
        if (method.params != null && !method.params.isEmpty()) {
            for (String param : method.params.split(",")) {
                String remappedParam = remapper.map(param.trim().replace('.', '/'));
                paramDesc.append(toDescriptor(remappedParam));
            }
        }
        paramDesc.append(")");

        String descriptor = paramDesc + returnDesc;

        // Skip if method already exists
        if (!existingMethods.add(method.name + descriptor)) {
            return;
        }

        MethodVisitor mv = super.visitMethod(
            Opcodes.ACC_PUBLIC,
            method.name,
            descriptor,
            null,
            null
        );

        if (mv != null) {
            mv.visitCode();

            if (!method.instructions.isEmpty()) {
                for (MappingConfig.Instruction insn : method.instructions) {
                    emitInstruction(mv, insn);
                }
            } else {
                generateDefaultReturn(mv, returnDesc);
            }

            mv.visitMaxs(4, 4);
            mv.visitEnd();
        }
    }

    private void emitInstruction(MethodVisitor mv, MappingConfig.Instruction insn) {
        switch (insn.opcode) {
            case "getstatic" -> {
                String owner = remapper.map(insn.args[0]);
                String name = insn.args[1];
                String type = remapper.map(insn.args[2]);
                mv.visitFieldInsn(Opcodes.GETSTATIC, owner, name, "L" + type + ";");
            }
            case "putstatic" -> {
                String owner = remapper.map(insn.args[0]);
                String name = insn.args[1];
                String type = remapper.map(insn.args[2]);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, name, "L" + type + ";");
            }
            case "areturn" -> mv.visitInsn(Opcodes.ARETURN);
            case "return" -> mv.visitInsn(Opcodes.RETURN);
            case "aconst_null" -> mv.visitInsn(Opcodes.ACONST_NULL);
            case "checkcast" -> mv.visitTypeInsn(Opcodes.CHECKCAST, remapper.map(insn.args[0]));
            case "aload" -> mv.visitVarInsn(Opcodes.ALOAD, Integer.parseInt(insn.args[0]));
            case "invokevirtual" -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                remapper.map(insn.args[0]), insn.args[1], insn.args[2], isItf(insn, false));
            case "invokestatic" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                remapper.map(insn.args[0]), insn.args[1], insn.args[2], isItf(insn, false));
            case "invokeinterface" -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                remapper.map(insn.args[0]), insn.args[1], insn.args[2], isItf(insn, true));
        }
    }

    /** Read the optional itf flag from an invoke instruction's args[3], falling back to the default. */
    private static boolean isItf(MappingConfig.Instruction insn, boolean defaultValue) {
        if (insn.args.length > 3) {
            return "true".equals(insn.args[3]);
        }
        return defaultValue;
    }

    private void generateDefaultReturn(MethodVisitor mv, String returnDesc) {
        if (returnDesc.equals("V")) {
            mv.visitInsn(Opcodes.RETURN);
        } else if (returnDesc.equals("I") || returnDesc.equals("Z") || returnDesc.equals("B")
                   || returnDesc.equals("C") || returnDesc.equals("S")) {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        } else if (returnDesc.equals("J")) {
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LRETURN);
        } else if (returnDesc.equals("F")) {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.FRETURN);
        } else if (returnDesc.equals("D")) {
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DRETURN);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    private String toDescriptor(String internalName) {
        return switch (internalName) {
            case "void" -> "V";
            case "int" -> "I";
            case "long" -> "J";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + internalName + ";";
        };
    }
}
