import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Static mixin audit: checks every mixin of the given mod jars (including nested jar-in-jar jars) against the
 * real target classes (NeoForge-patched Minecraft + NeoForge + libraries) without booting the game.
 *
 * Usage: java -cp asm.jar:asm-tree.jar MixinAudit.java <targets-list-file> <out-file> <mod-jar>...
 *   targets-list-file: one jar path per line, highest priority (patched MC) first.
 */
public class MixinAudit {
    record Src(String jar, String entry, byte[] bytes) {}

    static final Map<String, Src> TARGETS = new HashMap<>();   // internal name -> class (MC/Neo/libs)
    static final Map<String, Src> MODS = new HashMap<>();      // internal name -> class (mod jars)
    static final Map<String, ClassNode> NODE_CACHE = new HashMap<>();
    static final List<String[]> CONFIGS = new ArrayList<>();   // {origin, json}
    static final TreeMap<String, TreeSet<String>> ISSUES = new TreeMap<>();
    static int mixinCount, injectorCount, checkedAts;
    static final Map<String, Set<String>> ADDED_FIELDS = new HashMap<>();   // target -> field names added by any mixin
    static final Map<String, Set<String>> ADDED_METHODS = new HashMap<>();  // target -> name+desc added by any mixin

    static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final String PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;";
    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    static final Set<String> MEMBER_ANNOTATIONS_OK = Set.of(
        "Lorg/spongepowered/asm/mixin/Unique;", "Lorg/spongepowered/asm/mixin/Overwrite;",
        "Lorg/spongepowered/asm/mixin/Intrinsic;", SHADOW, ACCESSOR, INVOKER);

    public static void main(String[] args) throws Exception {
        List<String> targetJars = Files.readAllLines(Path.of(args[0])).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String jar : targetJars) indexJar(jar, jar, TARGETS, false);
        for (int i = 2; i < args.length; i++) indexJar(args[i], new File(args[i]).getName(), MODS, true);

        StringBuilder head = new StringBuilder();
        head.append("target jars: ").append(targetJars.size()).append(", target classes: ").append(TARGETS.size())
            .append(", mod classes: ").append(MODS.size()).append(", mixin configs: ").append(CONFIGS.size()).append('\n');
        for (String probe : List.of("net/minecraft/server/MinecraftServer", "net/minecraft/client/Minecraft", "net/minecraft/world/entity/Entity")) {
            Src s = TARGETS.get(probe);
            head.append("  ").append(probe).append(" <- ").append(s == null ? "MISSING" : s.jar()).append('\n');
        }

        for (String[] cfg : CONFIGS) collectAdded(cfg[1]);
        for (String[] cfg : CONFIGS) auditConfig(cfg[0], cfg[1]);

        int total = ISSUES.values().stream().mapToInt(Set::size).sum();
        head.append("mixins: ").append(mixinCount).append(", injectors: ").append(injectorCount)
            .append(", @At targets checked: ").append(checkedAts).append(", ISSUES: ").append(total).append("\n\n");
        try (PrintWriter w = new PrintWriter(new FileWriter(args[1]))) {
            w.print(head);
            ISSUES.forEach((kind, set) -> {
                w.println("== " + kind + " (" + set.size() + ")");
                set.forEach(w::println);
                w.println();
            });
        }
        System.out.print(head);
    }

    // ---- indexing -------------------------------------------------------------------------------------------

    static void indexJar(String path, String origin, Map<String, Src> into, boolean collectConfigs) {
        try (InputStream in = new FileInputStream(path)) {
            indexStream(in, path, origin, into, collectConfigs);
        } catch (IOException e) {
            System.err.println("cannot read " + path + ": " + e);
        }
    }

    static void indexStream(InputStream in, String jar, String origin, Map<String, Src> into, boolean collectConfigs) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
            String n = e.getName();
            if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                String name = n.substring(0, n.length() - 6);
                into.putIfAbsent(name, new Src(jar, n, zip.readAllBytes()));
            } else if (n.endsWith(".jar") && collectConfigs) {
                byte[] nested = zip.readAllBytes();
                indexStream(new ByteArrayInputStream(nested), jar + "!" + n, n.substring(n.lastIndexOf('/') + 1), into, true);
            } else if (collectConfigs && n.endsWith(".json") && !n.contains("/")) {
                String json = new String(zip.readAllBytes());
                if (json.contains("\"package\"") && (json.contains("\"mixins\"") || json.contains("\"client\"") || json.contains("\"server\"")))
                    CONFIGS.add(new String[]{origin + ":" + n, json});
            }
        }
    }

    static ClassNode node(String internalName) {
        if (NODE_CACHE.containsKey(internalName)) return NODE_CACHE.get(internalName);
        Src s = TARGETS.get(internalName);
        if (s == null) s = MODS.get(internalName);
        ClassNode cn = null;
        if (s != null) {
            cn = new ClassNode();
            new ClassReader(s.bytes()).accept(cn, 0);
        }
        NODE_CACHE.put(internalName, cn);
        return cn;
    }

    // ---- minimal JSON helpers (mixin configs are simple) ----------------------------------------------------

    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static List<String> jsonArray(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL).matcher(json);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (s.find()) out.add(s.group(1));
        }
        return out;
    }

    // ---- audit ----------------------------------------------------------------------------------------------

    static void issue(String kind, String msg) {
        ISSUES.computeIfAbsent(kind, k -> new TreeSet<>()).add(msg);
    }

    static void collectAdded(String json) {
        String pkg = jsonString(json, "package");
        if (pkg == null) return;
        for (String side : List.of("mixins", "client", "server")) {
            for (String m : jsonArray(json, side)) {
                ClassNode mixin = node((pkg + "." + m).replace('.', '/'));
                if (mixin == null) continue;
                AnnotationNode mixAnn = find(anns(mixin.visibleAnnotations, mixin.invisibleAnnotations), MIXIN);
                if (mixAnn == null) continue;
                List<String> targets = new ArrayList<>();
                for (Object o : list(val(mixAnn, "value"))) targets.add(((Type) o).getInternalName());
                for (Object o : list(val(mixAnn, "targets"))) targets.add(((String) o).replace('.', '/'));
                for (String t : targets) {
                    for (FieldNode f : mixin.fields)
                        if (find(anns(f.visibleAnnotations, f.invisibleAnnotations), SHADOW) == null) ADDED_FIELDS.computeIfAbsent(t, k -> new HashSet<>()).add(f.name);
                    for (MethodNode mm : mixin.methods)
                        if (find(anns(mm.visibleAnnotations, mm.invisibleAnnotations), SHADOW) == null) ADDED_METHODS.computeIfAbsent(t, k -> new HashSet<>()).add(mm.name + mm.desc);
                }
            }
        }
    }

    static void auditConfig(String origin, String json) {
        String pkg = jsonString(json, "package");
        if (pkg == null) return;
        for (String side : List.of("mixins", "client", "server")) {
            for (String m : jsonArray(json, side)) {
                String cls = (pkg + "." + m).replace('.', '/');
                String where = origin + " [" + side + "] " + m;
                ClassNode mixin = node(cls);
                if (mixin == null) { issue("MIXIN_CLASS_MISSING", where + " :: class " + cls + " not found in mod jars"); continue; }
                mixinCount++;
                auditMixin(where, mixin);
            }
        }
    }

    static List<AnnotationNode> anns(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> l = new ArrayList<>();
        if (a != null) l.addAll(a);
        if (b != null) l.addAll(b);
        return l;
    }

    static AnnotationNode find(List<AnnotationNode> l, String desc) {
        for (AnnotationNode a : l) if (a.desc.equals(desc)) return a;
        return null;
    }

    static Object val(AnnotationNode a, String key) {
        if (a == null || a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return (List<Object>) l;
        return List.of(o);
    }

    static void auditMixin(String where, ClassNode mixin) {
        List<AnnotationNode> ca = anns(mixin.visibleAnnotations, mixin.invisibleAnnotations);
        AnnotationNode mixAnn = find(ca, MIXIN);
        if (mixAnn == null) { issue("NOT_A_MIXIN", where + " :: no @Mixin annotation"); return; }
        boolean pseudo = find(ca, PSEUDO) != null;
        List<String> targets = new ArrayList<>();
        for (Object o : list(val(mixAnn, "value"))) targets.add(((Type) o).getInternalName());
        for (Object o : list(val(mixAnn, "targets"))) targets.add(((String) o).replace('.', '/'));

        for (String t : targets) {
            ClassNode target = node(t);
            if (target == null) {
                if (!pseudo) issue("TARGET_CLASS_MISSING", where + " :: @Mixin target " + t);
                continue;
            }
            auditAgainst(where, mixin, target);
        }
    }

    static void auditAgainst(String where, ClassNode mixin, ClassNode target) {
        String tn = target.name;
        for (MethodNode m : mixin.methods) {
            List<AnnotationNode> ma = anns(m.visibleAnnotations, m.invisibleAnnotations);
            AnnotationNode inj = null;
            for (AnnotationNode a : ma) if (INJECTORS.contains(a.desc)) inj = a;
            String sig = where + " -> " + tn + " :: " + m.name + m.desc;

            if (inj != null) {
                injectorCount++;
                String injName = inj.desc.substring(inj.desc.lastIndexOf('/') + 1, inj.desc.length() - 1);
                List<MethodNode> matched = new ArrayList<>();
                List<Object> selectors = list(val(inj, "method"));
                for (Object sel : selectors) {
                    List<MethodNode> r = resolveSelector(target, (String) sel);
                    if (r.isEmpty()) issue("INJECTOR_METHOD_NOT_FOUND", sig + " :: @" + injName + " method=\"" + sel + "\"" + candidates(target, (String) sel));
                    matched.addAll(r);
                }
                if (matched.isEmpty()) continue;
                List<Object> ats = new ArrayList<>(list(val(inj, "at")));
                for (Object at : ats) checkAt(sig, injName, (AnnotationNode) at, matched);
                continue;
            }

            AnnotationNode shadow = find(ma, SHADOW);
            if (shadow != null) {
                String name = m.name;
                Object prefix = val(shadow, "prefix");
                String p = prefix == null ? "shadow$" : (String) prefix;
                if (name.startsWith(p)) name = name.substring(p.length());
                if (findMethod(target, name, m.desc, true) == null && !hasAlias(shadow, target, m.desc, true)
                    && !ADDED_METHODS.getOrDefault(tn, Set.of()).contains(name + m.desc))
                    issue("SHADOW_METHOD_MISSING", sig + " :: @Shadow " + name + m.desc + candidates(target, name));
                continue;
            }
            AnnotationNode accessor = find(ma, ACCESSOR);
            if (accessor != null) {
                String f = (String) val(accessor, "value");
                if (f == null || f.isEmpty()) f = derive(m.name, "get", "is", "set");
                if (findFieldAnyCase(target, f) == null) issue("ACCESSOR_FIELD_MISSING", sig + " :: @Accessor field " + f);
                continue;
            }
            AnnotationNode invoker = find(ma, INVOKER);
            if (invoker != null) {
                String n = (String) val(invoker, "value");
                if (n == null || n.isEmpty()) n = derive(m.name, "invoke", "call", "create", "new");
                Type[] args = Type.getArgumentTypes(m.desc);
                if (!invokerResolves(target, n, args)) issue("INVOKER_METHOD_MISSING", sig + " :: @Invoker " + n + Arrays.toString(args) + candidates(target, n));
                continue;
            }
            // Plain method merged into the target: clashes with an existing method unless annotated.
            boolean annotatedOk = ma.stream().anyMatch(a -> MEMBER_ANNOTATIONS_OK.contains(a.desc));
            if (!annotatedOk && !m.name.startsWith("<") && (m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT)) == 0
                && (target.access & Opcodes.ACC_INTERFACE) == 0) {
                for (MethodNode tm : target.methods) {
                    if (tm.name.equals(m.name) && tm.desc.equals(m.desc)) {
                        issue("CLASHES_WITH_TARGET_METHOD", sig + " :: target already declares " + m.name + m.desc + " (needs @Overwrite or removal)");
                    }
                }
            }
        }
        for (FieldNode f : mixin.fields) {
            List<AnnotationNode> fa = anns(f.visibleAnnotations, f.invisibleAnnotations);
            AnnotationNode shadow = find(fa, SHADOW);
            if (shadow == null) continue;
            String name = f.name;
            if (findField(target, name, f.desc) == null && !hasAlias(shadow, target, null, false)
                && !ADDED_FIELDS.getOrDefault(tn, Set.of()).contains(name))
                issue("SHADOW_FIELD_MISSING", where + " -> " + tn + " :: @Shadow " + f.desc + " " + name + fieldCandidates(target, name));
        }
    }

    static boolean hasAlias(AnnotationNode shadow, ClassNode target, String desc, boolean method) {
        for (Object a : list(val(shadow, "aliases"))) {
            if (method ? findMethod(target, (String) a, desc, true) != null : findField(target, (String) a, desc) != null) return true;
        }
        return false;
    }

    static String derive(String name, String... prefixes) {
        for (String p : prefixes) {
            if (name.startsWith(p) && name.length() > p.length()) {
                String rest = name.substring(p.length());
                if (p.equals("create") || p.equals("new")) return "<init>";
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    static boolean invokerResolves(ClassNode target, String name, Type[] args) {
        for (ClassNode c = target; c != null; c = c.superName == null ? null : node(c.superName)) {
            for (MethodNode tm : c.methods) {
                if (!tm.name.equals(name)) continue;
                if (Arrays.equals(Type.getArgumentTypes(tm.desc), args)) return true;
            }
            if (name.equals("<init>")) break;
        }
        return false;
    }

    static MethodNode findMethod(ClassNode target, String name, String desc, boolean hierarchy) {
        for (ClassNode c = target; c != null; c = (hierarchy && c.superName != null) ? node(c.superName) : null) {
            for (MethodNode tm : c.methods) if (tm.name.equals(name) && (desc == null || tm.desc.equals(desc))) return tm;
            if (hierarchy && c.interfaces != null) {
                for (String i : c.interfaces) {
                    ClassNode ic = node(i);
                    if (ic != null) for (MethodNode tm : ic.methods) if (tm.name.equals(name) && (desc == null || tm.desc.equals(desc))) return tm;
                }
            }
        }
        return null;
    }

    static FieldNode findField(ClassNode target, String name, String desc) {
        for (ClassNode c = target; c != null; c = c.superName == null ? null : node(c.superName))
            for (FieldNode f : c.fields) if (f.name.equals(name) && (desc == null || f.desc.equals(desc))) return f;
        return null;
    }

    static FieldNode findFieldAnyCase(ClassNode target, String name) {
        FieldNode f = findField(target, name, null);
        if (f != null) return f;
        for (ClassNode c = target; c != null; c = c.superName == null ? null : node(c.superName))
            for (FieldNode x : c.fields) if (x.name.equalsIgnoreCase(name) || x.name.equalsIgnoreCase(name.replace("_", ""))) return x;
        return null;
    }

    static String candidates(ClassNode target, String sel) {
        String base = sel.replaceAll("^L[^;]+;", "").replaceAll("\\(.*$", "").replace("*", "");
        String key = base.replaceAll("^lambda\\$", "").replaceAll("\\$\\d+$", "");
        if (key.length() < 3) return "";
        List<String> c = new ArrayList<>();
        for (MethodNode tm : target.methods) if (tm.name.contains(key)) c.add(tm.name + tm.desc);
        if (c.isEmpty()) return " | no similarly named methods";
        return " | candidates: " + String.join(", ", c.subList(0, Math.min(6, c.size())));
    }

    static String fieldCandidates(ClassNode target, String name) {
        List<String> c = new ArrayList<>();
        for (FieldNode f : target.fields) if (f.name.toLowerCase().contains(name.toLowerCase()) || name.toLowerCase().contains(f.name.toLowerCase())) c.add(f.desc + " " + f.name);
        return c.isEmpty() ? "" : " | candidates: " + String.join(", ", c);
    }

    /** Resolves a mixin method selector: name, name(desc), Lowner;name(desc), name*, *, <init>. */
    static List<MethodNode> resolveSelector(ClassNode target, String sel) {
        String s = sel.trim();
        if (s.startsWith("/")) {
            Matcher rm = Pattern.compile("^/(.*?)/(?:\\s+desc=/(.*?)/)?\\s*$").matcher(s);
            List<MethodNode> out = new ArrayList<>();
            if (rm.find()) {
                Pattern np = Pattern.compile(rm.group(1));
                Pattern dp = rm.group(2) == null ? null : Pattern.compile(rm.group(2));
                for (MethodNode tm : target.methods)
                    if (np.matcher(tm.name).find() && (dp == null || dp.matcher(tm.desc).find())) out.add(tm);
            }
            return out;
        }
        if (s.startsWith("L") && s.contains(";")) s = s.substring(s.indexOf(';') + 1);
        String name = s, desc = null;
        int p = s.indexOf('(');
        if (p >= 0) { name = s.substring(0, p); desc = s.substring(p); }
        else if (s.contains(":")) { name = s.substring(0, s.indexOf(':')); }
        List<MethodNode> out = new ArrayList<>();
        boolean wildcard = name.endsWith("*");
        String stem = wildcard ? name.substring(0, name.length() - 1) : name;
        for (MethodNode tm : target.methods) {
            boolean nameOk = stem.isEmpty() ? wildcard : (wildcard ? tm.name.startsWith(stem) : tm.name.equals(stem));
            if (!nameOk) continue;
            if (desc != null && !(tm.desc.equals(desc) || (desc.endsWith(")") && tm.desc.startsWith(desc)))) continue;
            out.add(tm);
        }
        return out;
    }

    static void checkAt(String sig, String injName, AnnotationNode at, List<MethodNode> matched) {
        String value = (String) val(at, "value");
        String targetStr = (String) val(at, "target");
        if (value == null || targetStr == null || targetStr.isEmpty()) return;
        if (value.startsWith("MIXINEXTRAS:")) return;
        checkedAts++;
        String t = targetStr.trim();
        // Normalise "pkg/Owner.name(desc)" / "pkg.Owner.name(desc)" to "Lpkg/Owner;name(desc)"
        if (!t.startsWith("L") && !t.startsWith("(") && (value.startsWith("INVOKE") || value.equals("FIELD"))) {
            int paren = t.indexOf('(') >= 0 ? t.indexOf('(') : (t.indexOf(':') >= 0 ? t.indexOf(':') : t.length());
            int dot = t.lastIndexOf('.', paren);
            if (dot > 0) t = "L" + t.substring(0, dot).replace('.', '/') + ";" + t.substring(dot + 1);
        }
        boolean found = false;
        switch (value) {
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                String owner = null, rest = t;
                if (t.startsWith("L") && t.contains(";")) { owner = t.substring(1, t.indexOf(';')); rest = t.substring(t.indexOf(';') + 1); }
                int p = rest.indexOf('(');
                String name = p >= 0 ? rest.substring(0, p) : rest;
                String desc = p >= 0 ? rest.substring(p) : null;
                for (MethodNode m : matched) for (AbstractInsnNode i : m.instructions) {
                    if (i instanceof MethodInsnNode mi && mi.name.equals(name) && (owner == null || mi.owner.equals(owner)) && (desc == null || mi.desc.equals(desc))) found = true;
                    if (i instanceof InvokeDynamicInsnNode) { /* lambdas: ignored */ }
                }
            }
            case "FIELD" -> {
                String owner = null, rest = t;
                if (t.startsWith("L") && t.contains(";")) { owner = t.substring(1, t.indexOf(';')); rest = t.substring(t.indexOf(';') + 1); }
                String name = rest.contains(":") ? rest.substring(0, rest.indexOf(':')) : rest;
                String desc = rest.contains(":") ? rest.substring(rest.indexOf(':') + 1) : null;
                for (MethodNode m : matched) for (AbstractInsnNode i : m.instructions)
                    if (i instanceof FieldInsnNode fi && fi.name.equals(name) && (owner == null || fi.owner.equals(owner)) && (desc == null || fi.desc.equals(desc))) found = true;
            }
            case "NEW" -> {
                String type;
                if (t.startsWith("(")) type = Type.getReturnType(t).getInternalName();          // "(args)Lowner;"
                else if (t.contains("(")) type = t.substring(0, t.indexOf('(')).replaceAll("^L|;$", "").replace('.', '/');
                else type = (t.startsWith("L") && t.endsWith(";") ? t.substring(1, t.length() - 1) : t).replace('.', '/');
                for (MethodNode m : matched) for (AbstractInsnNode i : m.instructions)
                    if (i instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW && ti.desc.equals(type)) found = true;
            }
            default -> { return; }
        }
        if (!found) {
            String names = matched.stream().map(m -> m.name + m.desc).distinct().limit(3).reduce((a, b) -> a + ", " + b).orElse("");
            issue("AT_TARGET_NOT_FOUND", sig + " :: @" + injName + " @At(" + value + ", \"" + t + "\") not present in " + names + atCandidates(t, matched));
        }
    }

    static String atCandidates(String t, List<MethodNode> matched) {
        String key = t.replaceAll("^L[^;]+;", "").replaceAll("[(:].*$", "");
        Set<String> c = new LinkedHashSet<>();
        for (MethodNode m : matched) for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode mi && mi.name.equals(key)) c.add("L" + mi.owner + ";" + mi.name + mi.desc);
            if (i instanceof FieldInsnNode fi && fi.name.equals(key)) c.add("L" + fi.owner + ";" + fi.name + ":" + fi.desc);
        }
        return c.isEmpty() ? "" : " | same-name calls: " + String.join(", ", c);
    }
}
