package consulo.maven.remap;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.util.*;

/**
 * Parses the XML mapping configuration.
 */
public class MappingConfig {
    /** Package-level rules: com/intellij/psi → consulo/language/psi (internal format uses /) */
    private final List<PackageRule> packageRules = new ArrayList<>();

    /** Per-class overrides: com/intellij/psi/FileViewProvider → consulo/language/file/FileViewProvider */
    private final Map<String, String> classRules = new HashMap<>();

    /** Method renames: "owner.methodName" → "newMethodName" */
    private final Map<String, String> methodRenames = new HashMap<>();

    /** Field type overrides: "owner.fieldName" → new type (internal name) */
    private final Map<String, String> fieldTypeOverrides = new HashMap<>();

    /** Classes that became interfaces (need invokestatic → interface invokestatic fix) */
    private final Set<String> classToInterface = new HashSet<>();

    /** Module definition */
    private ModuleDefinition moduleDefinition;

    /** Class modifications: add methods, fields, annotations */
    private final Map<String, ClassModification> modifications = new HashMap<>();

    public static MappingConfig parse(File xmlFile) throws Exception {
        MappingConfig config = new MappingConfig();
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(xmlFile);
        Element root = doc.getRootElement();

        // Parse package rules
        Element packages = root.getChild("packages");
        if (packages != null) {
            for (Element rule : packages.getChildren("rule")) {
                String from = rule.getAttributeValue("from").replace('.', '/');
                String to = rule.getAttributeValue("to").replace('.', '/');
                config.packageRules.add(new PackageRule(from, to));
            }
            // Sort by length descending (longest prefix first)
            config.packageRules.sort((a, b) -> Integer.compare(b.from.length(), a.from.length()));
        }

        // Parse class rules
        Element classes = root.getChild("classes");
        if (classes != null) {
            for (Element rule : classes.getChildren("rule")) {
                String from = rule.getAttributeValue("from").replace('.', '/');
                String to = rule.getAttributeValue("to").replace('.', '/');
                config.classRules.put(from, to);
                if ("interface".equals(rule.getAttributeValue("type"))) {
                    config.classToInterface.add(to);
                }
            }
        }

        // Parse method renames: owner.method → newName
        Element methods = root.getChild("methods");
        if (methods != null) {
            for (Element rule : methods.getChildren("rule")) {
                String owner = rule.getAttributeValue("owner").replace('.', '/');
                String name = rule.getAttributeValue("name");
                String newName = rule.getAttributeValue("newName");
                config.methodRenames.put(owner + "." + name, newName);
            }
        }

        // Parse modifications
        Element mods = root.getChild("modifications");
        if (mods != null) {
            for (Element classEl : mods.getChildren("class")) {
                String className = classEl.getAttributeValue("name").replace('.', '/');
                ClassModification mod = new ClassModification(className);

                for (Element rft : classEl.getChildren("replaceFieldType")) {
                    mod.fieldTypeReplacements.put(
                        rft.getAttributeValue("name"),
                        rft.getAttributeValue("type").replace('.', '/'));
                }

                for (Element ann : classEl.getChildren("addAnnotation")) {
                    mod.annotations.add(ann.getAttributeValue("type").replace('.', '/'));
                }

                for (Element method : classEl.getChildren("addMethod")) {
                    MethodToAdd m = new MethodToAdd();
                    m.name = method.getAttributeValue("name");
                    m.returnType = method.getAttributeValue("returnType").replace('.', '/');
                    m.params = method.getAttributeValue("params");

                    parseInstructions(method, m.instructions);

                    mod.methods.add(m);
                }

                for (Element override : classEl.getChildren("overrideMethod")) {
                    MethodOverride o = new MethodOverride();
                    o.name = override.getAttributeValue("name");
                    o.originalDesc = override.getAttributeValue("originalDesc");
                    o.changeAccess = override.getAttributeValue("changeAccess");

                    // <replaceReturn type="..."/>
                    Element replaceReturn = override.getChild("replaceReturn");
                    if (replaceReturn != null) {
                        o.replaceReturnType = replaceReturn.getAttributeValue("type").replace('.', '/');
                    }

                    // <replaceParam index="0" type="..."/>
                    for (Element rp : override.getChildren("replaceParam")) {
                        int index = Integer.parseInt(rp.getAttributeValue("index"));
                        String type = rp.getAttributeValue("type").replace('.', '/');
                        o.replaceParams.put(index, type);
                    }

                    // <removeParam index="1"/>
                    for (Element rm : override.getChildren("removeParam")) {
                        o.removeParams.add(Integer.parseInt(rm.getAttributeValue("index")));
                    }

                    // <addParam index="0" type="..."/>
                    for (Element ap : override.getChildren("addParam")) {
                        int index = Integer.parseInt(ap.getAttributeValue("index"));
                        String type = ap.getAttributeValue("type").replace('.', '/');
                        o.addParams.put(index, type);
                    }

                    // <body>...</body> — replace the method body with the given instructions
                    Element body = override.getChild("body");
                    if (body != null) {
                        o.bodyInstructions = new ArrayList<>();
                        parseInstructions(body, o.bodyInstructions);
                    }

                    mod.methodOverrides.add(o);
                }

                for (Element field : classEl.getChildren("addField")) {
                    FieldToAdd f = new FieldToAdd();
                    f.name = field.getAttributeValue("name");
                    f.type = field.getAttributeValue("type").replace('.', '/');
                    f.value = field.getAttributeValue("value");
                    mod.fields.add(f);
                }

                // Register field type overrides globally for remapper
                for (var entry : mod.fieldTypeReplacements.entrySet()) {
                    config.fieldTypeOverrides.put(className + "." + entry.getKey(), entry.getValue());
                }

                config.modifications.put(className, mod);
            }
        }

        // Parse module definition
        Element module = root.getChild("module");
        if (module != null) {
            ModuleDefinition md = new ModuleDefinition();
            md.name = module.getAttributeValue("name");

            for (Element req : module.getChildren("requires")) {
                md.requires.add(req.getTextTrim());
            }

            for (Element exp : module.getChildren("exports")) {
                md.exports.add(exp.getTextTrim().replace('.', '/'));
            }

            for (Element provides : module.getChildren("provides")) {
                String iface = provides.getAttributeValue("interface").replace('.', '/');
                ServiceProvision sp = new ServiceProvision(iface);
                for (Element impl : provides.getChildren("implementation")) {
                    sp.implementations.add(impl.getTextTrim().replace('.', '/'));
                }
                // Binding generation
                for (Element binding : provides.getChildren("binding")) {
                    BindingDeclaration bd = new BindingDeclaration();
                    bd.implClass = binding.getAttributeValue("impl").replace('.', '/');
                    bd.apiClass = binding.getAttributeValue("api").replace('.', '/');
                    bd.scope = binding.getAttributeValue("scope"); // APPLICATION, PROJECT, etc.
                    sp.bindings.add(bd);
                }
                md.provides.add(sp);
            }

            config.moduleDefinition = md;
        }

        return config;
    }

    /**
     * Remap an internal class name.
     * Checks class rules first (exact match), then package rules (longest prefix).
     */
    public String remapClass(String internalName) {
        // Check class rules first (exact match)
        String classResult = classRules.get(internalName);
        if (classResult != null) {
            return classResult;
        }

        // Check inner classes: Foo$Bar → remap Foo, keep $Bar
        int dollarIdx = internalName.indexOf('$');
        if (dollarIdx > 0) {
            String outer = internalName.substring(0, dollarIdx);
            String inner = internalName.substring(dollarIdx);
            String remappedOuter = classRules.get(outer);
            if (remappedOuter != null) {
                return remappedOuter + inner;
            }
        }

        // Fall back to package rules (longest prefix match)
        for (PackageRule rule : packageRules) {
            if (internalName.startsWith(rule.from + "/") || internalName.equals(rule.from)) {
                return rule.to + internalName.substring(rule.from.length());
            }
        }

        return internalName; // no mapping
    }

    public ClassModification getModification(String internalName) {
        return modifications.get(internalName);
    }

    public List<PackageRule> getPackageRules() {
        return packageRules;
    }

    public Map<String, String> getClassRules() {
        return classRules;
    }

    /**
     * Remap a method name given its (already-remapped) owner.
     * Returns the new name or the original if no rename applies.
     */
    public ModuleDefinition getModuleDefinition() {
        return moduleDefinition;
    }

    /**
     * Get overridden field type for a given owner.field, or null if no override.
     */
    public String getFieldTypeOverride(String owner, String fieldName) {
        return fieldTypeOverrides.get(owner + "." + fieldName);
    }

    public boolean isClassToInterface(String internalName) {
        return classToInterface.contains(internalName);
    }

    public String remapMethodName(String owner, String name) {
        String key = owner + "." + name;
        return methodRenames.getOrDefault(key, name);
    }

    /**
     * Parse bytecode instructions from XML children of the given element.
     * Shared by &lt;addMethod&gt; and &lt;body&gt; (inside &lt;overrideMethod&gt;).
     *
     * For {@code invokevirtual}/{@code invokestatic}/{@code invokeinterface}, an
     * optional {@code itf="true|false"} attribute controls the {@code isInterface}
     * flag passed to {@link org.objectweb.asm.MethodVisitor#visitMethodInsn}.
     * Defaults: {@code invokeinterface} → true, others → false.
     */
    private static void parseInstructions(Element parent, List<Instruction> out) {
        for (Element insn : parent.getChildren()) {
            String tag = insn.getName();
            switch (tag) {
                case "getstatic" -> out.add(new Instruction("getstatic",
                    insn.getAttributeValue("owner").replace('.', '/'),
                    insn.getAttributeValue("name"),
                    insn.getAttributeValue("type").replace('.', '/')));
                case "areturn" -> out.add(new Instruction("areturn"));
                case "return" -> out.add(new Instruction("return"));
                case "aconst_null" -> out.add(new Instruction("aconst_null"));
                case "checkcast" -> out.add(new Instruction("checkcast",
                    insn.getAttributeValue("type").replace('.', '/')));
                case "invokevirtual", "invokestatic", "invokeinterface" -> {
                    String itfAttr = insn.getAttributeValue("itf");
                    String itf = itfAttr != null
                        ? itfAttr
                        : (tag.equals("invokeinterface") ? "true" : "false");
                    out.add(new Instruction(tag,
                        insn.getAttributeValue("owner").replace('.', '/'),
                        insn.getAttributeValue("name"),
                        insn.getAttributeValue("desc"),
                        itf));
                }
                case "aload" -> out.add(new Instruction("aload",
                    insn.getAttributeValue("index")));
            }
        }
    }

    // Data classes

    public record PackageRule(String from, String to) {}

    public static class ClassModification {
        public final String className;
        public final Map<String, String> fieldTypeReplacements = new HashMap<>(); // fieldName → newType
        public final List<String> annotations = new ArrayList<>();
        public final List<MethodToAdd> methods = new ArrayList<>();
        public final List<FieldToAdd> fields = new ArrayList<>();
        public final List<MethodOverride> methodOverrides = new ArrayList<>();

        public ClassModification(String className) {
            this.className = className;
        }
    }

    public static class MethodToAdd {
        public String name;
        public String returnType;
        public String params;
        public final List<Instruction> instructions = new ArrayList<>();
    }

    public static class Instruction {
        public final String opcode;
        public final String[] args;

        public Instruction(String opcode, String... args) {
            this.opcode = opcode;
            this.args = args;
        }
    }

    public static class FieldToAdd {
        public String name;
        public String type;
        public String value;
    }

    public static class MethodOverride {
        public String name;
        public String originalDesc;                            // original descriptor to match, or null for name-only
        public String changeAccess;                            // "public"/"protected"/"private", or null
        public String replaceReturnType;                       // new return type, or null to keep
        public final Map<Integer, String> replaceParams = new TreeMap<>();  // index → new type
        public final Set<Integer> removeParams = new TreeSet<>();           // indices to remove
        public final Map<Integer, String> addParams = new TreeMap<>();      // index → type to insert
        public List<Instruction> bodyInstructions;             // non-null ⇒ replace the original method body
    }

    public static class ModuleDefinition {
        public String name;
        public final List<String> requires = new ArrayList<>();
        public final List<String> exports = new ArrayList<>();
        public final List<ServiceProvision> provides = new ArrayList<>();
    }

    public static class ServiceProvision {
        public final String interfaceName;
        public final List<String> implementations = new ArrayList<>();
        public final List<BindingDeclaration> bindings = new ArrayList<>();

        public ServiceProvision(String interfaceName) {
            this.interfaceName = interfaceName;
        }
    }

    public static class BindingDeclaration {
        public String implClass;
        public String apiClass;
        public String scope; // APPLICATION, PROJECT, etc.
    }
}
