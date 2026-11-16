package consulo.maven.javaBytecodeRemap;

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

    /** Call redirects keyed by "name" + descriptor (remapped names); see {@link Redirect}. */
    private final Map<String, List<Redirect>> methodRedirects = new HashMap<>();

    /** Instantiations redirected to another class: owner → replacement (both remapped internal names). */
    private final Map<String, String> newRedirects = new HashMap<>();
    private final Map<String, String[]> instanceofRedirects = new HashMap<>();
    private final Set<String> constructorDrops = new HashSet<>();

    /** Static field reads redirected to a static getter: "owner.name" → redirect. */
    private final Map<String, Redirect> fieldRedirects = new HashMap<>();

    /** Classes (or packages) copied unchanged: they use names that rules would rewrite on purpose. */
    private final List<String> verbatim = new ArrayList<>();

    public static MappingConfig parse(File xmlFile) throws Exception {
        MappingConfig config = new MappingConfig();
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(xmlFile);
        Element root = doc.getRootElement();

        // Classes copied unchanged: <verbatim name="a.b.C"/> (the class, its nested classes, or a package)
        for (Element v : root.getChildren("verbatim")) {
            config.verbatim.add(v.getAttributeValue("name").replace('.', '/'));
        }

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

        // Parse call redirects (owner, name and desc use remapped names)
        Element redirects = root.getChild("redirects");
        if (redirects != null) {
            for (Element r : redirects.getChildren("method")) {
                Redirect redirect = new Redirect(
                    r.getAttributeValue("owner").replace('.', '/'),
                    r.getAttributeValue("name"),
                    r.getAttributeValue("desc"),
                    "true".equals(r.getAttributeValue("anyOwner")),
                    r.getAttributeValue("target").replace('.', '/'),
                    Objects.requireNonNullElse(r.getAttributeValue("targetName"), r.getAttributeValue("name")),
                    r.getAttributeValue("targetDesc"));
                config.methodRedirects.computeIfAbsent(redirect.name() + redirect.desc(), k -> new ArrayList<>()).add(redirect);
            }
            for (Element r : redirects.getChildren("field")) {
                Redirect redirect = new Redirect(
                    r.getAttributeValue("owner").replace('.', '/'),
                    r.getAttributeValue("name"),
                    r.getAttributeValue("desc"),
                    false,
                    r.getAttributeValue("target").replace('.', '/'),
                    Objects.requireNonNullElse(r.getAttributeValue("targetName"), r.getAttributeValue("name")),
                    null);
                config.fieldRedirects.put(redirect.owner() + "." + redirect.name(), redirect);
            }
            // instanceof T -> static boolean target.targetName(Object): a kind check the type itself cannot express
            for (Element r : redirects.getChildren("instanceof")) {
                config.instanceofRedirects.put(r.getAttributeValue("type").replace('.', '/'),
                    new String[]{r.getAttributeValue("target").replace('.', '/'), r.getAttributeValue("targetName")});
            }
            // a constructor the target lacks, whose first argument it does without: owner(A, B) -> owner(B)
            for (Element r : redirects.getChildren("dropFirstConstructorArgument")) {
                config.constructorDrops.add(r.getAttributeValue("owner").replace('.', '/') + "." + r.getAttributeValue("desc"));
            }
            for (Element r : redirects.getChildren("new")) {
                config.newRedirects.put(r.getAttributeValue("owner").replace('.', '/'), r.getAttributeValue("target").replace('.', '/'));
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
                    String type = ann.getAttributeValue("type").replace('.', '/');
                    mod.annotations.add(type);
                    // <enum name="value" type="pkg.Enum" value="CONSTANT"/> elements
                    for (Element e : ann.getChildren("enum")) {
                        mod.annotationEnums.computeIfAbsent(type, k -> new ArrayList<>()).add(new String[]{
                            e.getAttributeValue("name"), e.getAttributeValue("type").replace('.', '/'), e.getAttributeValue("value")});
                    }
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
            md.exportAll = "true".equals(module.getAttributeValue("exportAll"));

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
                    bd.apiClass = binding.getAttributeValue("api").replace('.', '/');
                    // a topicBinding has no implementation: its binding class is named after the topic interface
                    bd.implClass = Objects.requireNonNullElse(binding.getAttributeValue("impl"), bd.apiClass.replace('/', '.')).replace('.', '/');
                    bd.scope = binding.getAttributeValue("scope"); // APPLICATION, PROJECT, etc.
                    bd.type = binding.getAttributeValue("type"); // extension (default), service, topic, topicBinding
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
    /** Whether a class is copied unchanged (see {@code <verbatim>}). */
    public boolean isVerbatim(String internalName) {
        for (String name : verbatim) {
            if (internalName.equals(name) || internalName.startsWith(name + "/") || internalName.startsWith(name + "$")) {
                return true;
            }
        }
        return false;
    }

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
        if (moduleDefinition != null) {
            moduleDefinition.assignBindingClasses();
        }
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

    /** Rename rules for a method name: rule owner -> new name. */
    public Map<String, String> methodRenamesByName(String name) {
        Map<String, String> result = new HashMap<>();
        String suffix = "." + name;
        methodRenames.forEach((key, newName) -> {
            if (key.endsWith(suffix)) {
                result.put(key.substring(0, key.length() - suffix.length()), newName);
            }
        });
        return result;
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

    /** Adds the rules of {@code other}; its entries win where both define the same key. */
    public void mergeFrom(MappingConfig other) {
        packageRules.addAll(other.packageRules);
        packageRules.sort((a, b) -> Integer.compare(b.from.length(), a.from.length()));
        classRules.putAll(other.classRules);
        methodRenames.putAll(other.methodRenames);
        fieldTypeOverrides.putAll(other.fieldTypeOverrides);
        classToInterface.addAll(other.classToInterface);
        modifications.putAll(other.modifications);
        other.methodRedirects.forEach((key, list) -> methodRedirects.computeIfAbsent(key, k -> new ArrayList<>()).addAll(0, list));
        fieldRedirects.putAll(other.fieldRedirects);
        newRedirects.putAll(other.newRedirects);
        instanceofRedirects.putAll(other.instanceofRedirects);
        constructorDrops.addAll(other.constructorDrops);
        verbatim.addAll(other.verbatim);
        if (other.moduleDefinition != null) {
            // the jar being remapped is one module; the last file that defines it wins
            moduleDefinition = other.moduleDefinition;
        }
    }

    /**
     * Redirect for a call site whose target does not exist in the new API.
     * Instance calls become {@code invokestatic target.targetName(Owner, args...)}; static calls keep
     * their descriptor. With {@code anyOwner}, calls are matched by name and descriptor only (calls made
     * through subclasses); the receiver is then passed as {@code owner}.
     */
    public Redirect findMethodRedirect(String owner, String name, String desc) {
        return findMethodRedirect(owner, name, desc, null);
    }

    /**
     * As {@link #findMethodRedirect(String, String, String)}; an {@code anyOwner} redirect is skipped when
     * {@code kinds} knows that {@code owner} is not a subtype of the redirect's owner (an unrelated class with a
     * method of the same name and descriptor).
     */
    public Redirect findMethodRedirect(String owner, String name, String desc, ClassKindIndex kinds) {
        List<Redirect> list = methodRedirects.get(name + desc);
        if (list == null) {
            return null;
        }
        for (Redirect r : list) {
            if (r.owner().equals(owner)) {
                return r;
            }
        }
        for (Redirect r : list) {
            if (r.anyOwner() && (kinds == null || !Boolean.FALSE.equals(kinds.isSubtype(owner, r.owner())))) {
                return r;
            }
        }
        return null;
    }

    /** Class to instantiate instead of {@code owner} (e.g. a class that became an interface), or null. */
    /** Whether a call of constructor {@code owner(desc)} drops its first argument (the descriptor has exactly two, of one slot each). */
    public boolean dropsFirstConstructorArgument(String owner, String desc) {
        return constructorDrops.contains(owner + "." + desc);
    }

    /** [target class, method name] for {@code instanceof type}, or null. */
    public String[] findInstanceofRedirect(String type) {
        return instanceofRedirects.get(type);
    }

    public String findNewRedirect(String owner) {
        return newRedirects.get(owner);
    }

    public Redirect findFieldRedirect(String owner, String name) {
        return fieldRedirects.get(owner + "." + name);
    }

    /** {@code targetDesc}: the target's descriptor when it differs from the call's (e.g. a wider return type); null if the same. */
    public record Redirect(String owner, String name, String desc, boolean anyOwner, String target, String targetName, String targetDesc) {}

    public record PackageRule(String from, String to) {}

    public static class ClassModification {
        public final String className;
        public final Map<String, String> fieldTypeReplacements = new HashMap<>(); // fieldName → newType
        public final List<String> annotations = new ArrayList<>();
        /** Enum elements of added annotations: annotation type -> [name, enum type, constant]. */
        public final Map<String, List<String[]>> annotationEnums = new HashMap<>();
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
        /** Export every package of the jar (e.g. a library module), in addition to {@link #exports}. */
        public boolean exportAll;
        public final List<String> requires = new ArrayList<>();
        public final List<String> exports = new ArrayList<>();
        public final List<ServiceProvision> provides = new ArrayList<>();

        /**
         * Names the generated binding classes: {@code Impl_Binding}, and {@code Impl_Api_Binding} for further bindings
         * of the same implementation (e.g. a class that is two extensions).
         */
        void assignBindingClasses() {
            Set<String> named = new HashSet<>();
            for (ServiceProvision provision : provides) {
                for (BindingDeclaration binding : provision.bindings) {
                    if (binding.bindingClass != null) {
                        named.add(binding.bindingClass);
                        continue;
                    }
                    if ("topicBinding".equals(binding.type)) {
                        binding.bindingClass = binding.apiClass + "_Binding";
                        continue;
                    }
                    String name = binding.implClass + "_Binding";
                    if (!named.add(name)) {
                        name = binding.implClass + "_" + binding.apiClass.substring(binding.apiClass.lastIndexOf('/') + 1) + "_Binding";
                        named.add(name);
                    }
                    binding.bindingClass = name;
                }
            }
        }
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
        public String type; // extension (default), service, topic, topicBinding (a TopicBinding of the api)
        public String bindingClass; // the generated class (see ModuleDefinition.assignBindingClasses)
    }
}
