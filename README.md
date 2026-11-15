# Maven Java Bytecode Remap Plugin

A Maven plugin that rewrites Java bytecode at build time from an XML mapping file. It retargets code written against the IntelliJ Platform API onto the Consulo API: package renames, class renames, split packages, class-to-interface changes, signature fixups, synthesised methods/fields/annotations, and a generated `module-info` plus Consulo service bindings.

The heavy lifting is ASM 9.7.1. The configuration is one `remap.xml` per project.

## Goals

| Goal | Binds to | Purpose |
|---|---|---|
| `java-bytecode-remap:remap-jar` | `package` | Rewrite an already-built JAR (typically a shaded uberjar) in place. Can also inject a synthetic `module-info` and Consulo service bindings. |
| `java-bytecode-remap:remap` | `process-classes` | Aggregate classes from dependency JARs, remap them, and write to `${project.build.outputDirectory}` so `maven-jar-plugin` packages them normally. |

Pick `remap-jar` if you already use `maven-shade-plugin`. Pick `remap` if you want the plugin to pull classes out of dependency JARs itself.

## Pipeline

```
           ┌────────────────┐
           │ your sources   │  compile against IntelliJ-style stubs
           └───────┬────────┘
                   │ javac
                   ▼
           ┌────────────────┐
           │ classes / jars │
           └───────┬────────┘
                   │ maven-shade-plugin (optional, bundles deps)
                   ▼
           ┌────────────────┐
           │ shaded.jar     │
           └───────┬────────┘
                   │ java-bytecode-remap:remap-jar
                   ▼
    ┌──────────────────────────────────┐
    │ ClassRemapper ────────────►      │  package/class rewrite via remap.xml
    │   └─ CallSiteFixVisitor ─►       │  fix signatures ClassRemapper misses
    │       └─ ClassModifierVisitor ─► │  add methods/fields/annotations,
    │                                  │  override method descriptors
    └────────────────┬─────────────────┘
                     │
                     ▼
    ┌──────────────────────────────────┐
    │ BindingGenerator                 │  synthesise Consulo service bindings
    │ ModuleInfoGenerator              │  synthesise module-info.class
    └────────────────┬─────────────────┘
                     ▼
              remapped.jar  (replaces shaded.jar in place)
```

## Usage

Typical `remap-jar` wiring (runs after `maven-shade-plugin` in the same `package` phase):

```xml
<!-- Step 1: shade everything into one JAR -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.0</version>
    <executions>
        <execution>
            <id>shade</id>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <artifactSet>
                    <includes><include>your.group:*</include></includes>
                </artifactSet>
            </configuration>
        </execution>
    </executions>
</plugin>

<!-- Step 2: remap the shaded JAR in place -->
<plugin>
    <groupId>consulo.maven</groupId>
    <artifactId>maven-java-bytecode-remap-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <id>remap</id>
            <phase>package</phase>
            <goals><goal>remap-jar</goal></goals>
            <configuration>
                <mappingFile>${project.basedir}/remap.xml</mappingFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`remap-jar` parameters:

| Parameter | Required | Default | Description |
|---|---|---|---|
| `mappingFile` | yes | — | Path to the `remap.xml` file. |
| `inputJar` | no | the project's main JAR | JAR to rewrite in place. |
| `excludeResources` | no | — | Glob patterns for resource entries to drop (e.g. `org/jetbrains/annotations/**`). |

`remap` parameters:

| Parameter | Required | Default | Description |
|---|---|---|---|
| `mappingFile` | yes | — | Path to the `remap.xml` file. |
| `includeGroupIds` | no | — | Comma-separated groupId filter for dependency JARs to aggregate. |
| `excludeResources` | no | — | Glob patterns for resource entries to drop. |

## `remap.xml` schema

The schema below mirrors `MappingConfig.parse()` exactly — every element has a direct parser branch.

### `<packages>` — bulk prefix rewrite (lowest priority)

```xml
<packages>
    <rule from="com.intellij.psi"  to="consulo.language.psi"/>
    <rule from="com.intellij.lang" to="consulo.language"/>
</packages>
```

Rules are sorted longest-prefix-first, so `com.intellij.psi.impl` will always be tried before `com.intellij.psi`.

### `<classes>` — per-class overrides (highest priority)

```xml
<classes>
    <rule from="com.intellij.lang.ASTNode"           to="consulo.language.ast.ASTNode"/>
    <rule from="com.intellij.openapi.util.TextRange" to="consulo.document.util.TextRange"/>
    <rule from="javax.swing.Icon"                    to="consulo.ui.image.Image"/>
    <!-- Logger was a class in IntelliJ, interface in Consulo -->
    <rule from="com.intellij.openapi.diagnostic.Logger"
          to="consulo.logging.Logger" type="interface"/>
</classes>
```

`type="interface"` marks a class that became an interface in the target API; the plugin fixes call-site opcodes at bytecode level (`INVOKEVIRTUAL` → `INVOKEINTERFACE`). Inner classes (`Foo$Bar`) are handled automatically: if `Foo` has a rule, `Foo$Bar` maps to `<remappedFoo>$Bar`.

### `<methods>` — method renames (applied after class remapping)

```xml
<methods>
    <rule owner="consulo.application.util.NotNullLazyValue"
          name="lazy" newName="createValue"/>
</methods>
```

`owner` is the **remapped** class name.

### `<redirects>` — call sites rewritten to static helpers

For API that has no direct equivalent (a missing overload, an interface default, a static helper), point
the call at a static method of your own. Names and descriptors are the **remapped** ones (after
`<packages>`/`<classes>`/`<methods>`).

```xml
<redirects>
    <!-- instance call: becomes invokestatic Compat.getElementType(Lconsulo/language/psi/stub/StubElement;)... -->
    <method owner="consulo.language.psi.stub.StubElement" name="getElementType"
            desc="()Lconsulo/language/ast/IElementType;" target="my.compat.StubCompat"/>
    <!-- anyOwner: also match calls made through subclasses (by name + desc) -->
    <method owner="consulo.language.psi.stub.StubElement" name="findChildStubByElementType" anyOwner="true"
            desc="(Lconsulo/language/ast/IElementType;)Lconsulo/language/psi/stub/StubElement;" target="my.compat.StubCompat"/>
    <!-- static call: same descriptor, new owner -->
    <method owner="consulo.util.lang.StringUtil" name="escapeStringCharacters"
            desc="(Ljava/lang/String;)Ljava/lang/String;" target="my.compat.StringCompat"/>
    <!-- static field read: becomes invokestatic target.targetName()desc -->
    <field owner="consulo.language.psi.PsiModificationTracker" name="TOPIC"
           desc="Lconsulo/component/messagebus/Topic;" target="my.compat.Topics" targetName="psiModificationTopic"/>
</redirects>
```

Instance calls pass the receiver as the first argument (typed as `owner`). `targetName` defaults to `name`.

`<new owner="..." target="..."/>` instantiates `target` instead of `owner` (for a class that became an interface
in the new API). Only `new Owner(...)` sites change — `NEW` and its constructor call — so `target` must have the
same constructors and be assignable to `owner`; `super(...)` calls of subclasses are left alone.

### `<modifications>` — structural class edits

```xml
<modifications>
    <class name="org.jetbrains.kotlin.parsing.KotlinCommonParserDefinition">
        <overrideMethod name="createLexer">
            <replaceParam index="0" type="consulo.language.version.LanguageVersion"/>
        </overrideMethod>
        <overrideMethod name="getWhitespaceTokens">
            <addParam index="0" type="consulo.language.version.LanguageVersion"/>
        </overrideMethod>
    </class>

    <class name="org.jetbrains.kotlin.parsing.KotlinParserDefinition">
        <addAnnotation type="consulo.annotation.component.ExtensionImpl"/>
        <addMethod name="getLanguage" returnType="consulo.language.Language">
            <getstatic owner="org.jetbrains.kotlin.idea.KotlinLanguage"
                       name="INSTANCE"
                       type="org.jetbrains.kotlin.idea.KotlinLanguage"/>
            <areturn/>
        </addMethod>
    </class>
</modifications>
```

Children of `<class>`:

| Element | Attributes | Effect |
|---|---|---|
| `<addAnnotation>` | `type` | Add a class-level annotation. |
| `<addField>` | `name`, `type`, `value` | Add a field (optionally with an initial constant value). |
| `<replaceFieldType>` | `name`, `type` | Change an existing field's declared type; call sites get fixed up by `CallSiteFixVisitor`. |
| `<addMethod>` | `name`, `returnType`, `params` | Add a new method. Body is written as raw ASM instructions — see below. |
| `<overrideMethod>` | `name`, `originalDesc?`, `changeAccess?` | Rewrite an existing method's descriptor. Use `originalDesc` to disambiguate overloads, `changeAccess` (`public`/`protected`/`private`) to change visibility. |

Children of `<overrideMethod>`:

| Element | Attributes | Effect |
|---|---|---|
| `<replaceReturn>` | `type` | Change the return type. |
| `<replaceParam>` | `index`, `type` | Change a parameter's type. |
| `<addParam>` | `index`, `type` | Insert a parameter at that index. |
| `<removeParam>` | `index` | Drop a parameter. |

Children of `<addMethod>` (raw bytecode — each tag is one JVM instruction):

| Tag | Attributes |
|---|---|
| `<aload>` | `index` |
| `<aconst_null>` | — |
| `<getstatic>` | `owner`, `name`, `type` |
| `<checkcast>` | `type` |
| `<invokevirtual>` / `<invokestatic>` / `<invokeinterface>` | `owner`, `name`, `desc` |
| `<areturn>` | — |
| `<return>` | — |

### `<module>` — synthesised `module-info` and service bindings

```xml
<module name="consulo.kotlin.remapped">
    <requires>consulo.language.api</requires>
    <requires>consulo.language.impl</requires>
    <exports>org/jetbrains/kotlin/psi</exports>
    <exports>org/jetbrains/kotlin/parsing</exports>

    <provides interface="consulo.component.bind.InjectingBinding">
        <binding impl="org.jetbrains.kotlin.parsing.KotlinParserDefinition"
                 api="consulo.language.parser.ParserDefinition"
                 scope="APPLICATION"/>
    </provides>
</module>
```

- `<requires>` / `<exports>` — text content (dotted for requires, dotted-or-slashed for exports).
- `<provides interface="...">` with `<implementation>` children — standard JPMS `provides`.
- `<provides interface="...">` with `<binding>` children — additionally generates a Consulo `InjectingBinding` class that registers `impl` as the `api` implementation in the given `scope` (`APPLICATION`, `PROJECT`, …).

## Example — one rule of each kind

The Kotlin→Consulo migration's `remap.xml` is a good end-to-end reference. A condensed version:

```xml
<remap>
    <packages>
        <rule from="com.intellij.psi"  to="consulo.language.psi"/>
        <rule from="com.intellij.lang" to="consulo.language"/>
    </packages>

    <classes>
        <rule from="com.intellij.lang.ASTNode"           to="consulo.language.ast.ASTNode"/>
        <rule from="com.intellij.openapi.util.TextRange" to="consulo.document.util.TextRange"/>
        <rule from="javax.swing.Icon"                    to="consulo.ui.image.Image"/>
        <rule from="com.intellij.openapi.diagnostic.Logger"
              to="consulo.logging.Logger" type="interface"/>
    </classes>

    <methods>
        <rule owner="consulo.application.util.NotNullLazyValue"
              name="lazy" newName="createValue"/>
    </methods>

    <modifications>
        <class name="org.jetbrains.kotlin.parsing.KotlinCommonParserDefinition">
            <overrideMethod name="createLexer">
                <replaceParam index="0" type="consulo.language.version.LanguageVersion"/>
            </overrideMethod>
            <overrideMethod name="getWhitespaceTokens">
                <addParam index="0" type="consulo.language.version.LanguageVersion"/>
            </overrideMethod>
        </class>

        <class name="org.jetbrains.kotlin.parsing.KotlinParserDefinition">
            <addAnnotation type="consulo.annotation.component.ExtensionImpl"/>
            <addMethod name="getLanguage" returnType="consulo.language.Language">
                <getstatic owner="org.jetbrains.kotlin.idea.KotlinLanguage"
                           name="INSTANCE"
                           type="org.jetbrains.kotlin.idea.KotlinLanguage"/>
                <areturn/>
            </addMethod>
        </class>
    </modifications>

    <module name="consulo.kotlin.remapped">
        <requires>consulo.language.api</requires>
        <exports>org/jetbrains/kotlin/psi</exports>
        <provides interface="consulo.component.bind.InjectingBinding">
            <binding impl="org.jetbrains.kotlin.parsing.KotlinParserDefinition"
                     api="consulo.language.parser.ParserDefinition"
                     scope="APPLICATION"/>
        </provides>
    </module>
</remap>
```

Why each rule exists:

- **`<packages>`** — bulk rename for the hundreds of `com.intellij.*` → `consulo.*` classes that only differ in prefix.
- **`<classes>` (non-interface)** — split packages. `com.intellij.lang` fans out into `consulo.language.ast`, `consulo.language.parser`, … so a single package rule can't cover it; each split class gets an exact override.
- **`<classes type="interface">`** — `Logger` was a concrete class in IntelliJ, an interface in Consulo. The remapper must emit `INVOKEINTERFACE` at every call site, not `INVOKEVIRTUAL`.
- **`<methods>`** — API rename. `NotNullLazyValue#lazy()` is called `createValue()` in Consulo.
- **`<overrideMethod>`** — Consulo's `ParserDefinition` takes `LanguageVersion` parameters that IntelliJ's version does not. `replaceParam`/`addParam` reshape the descriptor so the inherited Consulo interface is satisfied.
- **`<addMethod>`** — Consulo's `ParserDefinition` requires `getLanguage()`; the Kotlin source doesn't declare one. The plugin synthesises it: push `KotlinLanguage.INSTANCE`, return.
- **`<addAnnotation>`** — Consulo discovers extensions via `@ExtensionImpl`; adding the annotation makes `KotlinParserDefinition` discoverable at runtime.
- **`<module>` with `<binding>`** — ships the remapped jar as a JPMS module and wires `KotlinParserDefinition` as the application-scope `ParserDefinition` implementation Consulo injects.

## Caching

`remap-jar` stores a sha/stamp of the mapping file alongside the output and skips re-running when inputs are unchanged. A `CACHE_VERSION` constant inside `RemapJarMojo` is bumped whenever the plugin's own logic changes, invalidating every downstream project's cached output. If you change the plugin source, bump `CACHE_VERSION`.

`remap` does a comparable up-to-date check against the input dependency JARs and the current contents of `target/classes`.

## Building the plugin

```bash
mvn -f /path/to/maven-java-bytecode-remap-plugin/pom.xml clean install
```

Produces `consulo.maven:maven-java-bytecode-remap-plugin:1.0-SNAPSHOT` in your local repo. Requires JDK 21 and Maven 3.6+.

## Dependencies

- ASM 9.7.1 (`asm`, `asm-commons`) — bytecode read/write and the `Remapper` base.
- JDOM 2 — `remap.xml` parser.
- `maven-plugin-api` 3.9.6 + `maven-plugin-annotations` 3.11.0 (provided) — Mojo machinery.
- `maven-project` 2.2.1 (provided) — needed by `RemapMojo` to walk the dependency graph.
