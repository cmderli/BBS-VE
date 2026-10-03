import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

/** Validates @Mixin targets, @Inject/@Redirect/@ModifyArg/... method selectors, nested @At targets,
 *  @Accessor/@Invoker members and @Shadow members against the real Minecraft jar. */
public class MixinAudit {
    static Map<String,Set<String>> methods = new HashMap<>();
    static Map<String,Set<String>> fields = new HashMap<>();
    static Set<String> classes = new HashSet<>();
    static Map<String,String> supr = new HashMap<>();
    static int problems = 0;

    public static void main(String[] a) throws Exception {
        loadMc(a[0]);
        List<Path> files = Files.walk(Paths.get(a[1])).filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        for (Path p : files) {
            String rel = Paths.get(a[1]).relativize(p).toString();
            ctx = rel;
            try { audit(Files.readAllBytes(p), rel); }
            catch (Throwable t) { System.out.println("ERR " + p + " " + t); }
        }
        System.out.println("\n===== " + problems + " problem(s) across " + files.size() + " classes");
    }

    static void loadMc(String jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                byte[] b; try (InputStream in = zf.getInputStream(e)) { b = in.readAllBytes(); }
                try { new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                    String cn;
                    public void visit(int v,int ac,String n,String s,String su,String[] i){ cn=n; supr.put(n,su); classes.add(n); }
                    public MethodVisitor visitMethod(int ac,String n,String d,String s,String[] ex){ methods.computeIfAbsent(cn,k->new HashSet<>()).add(n+d); return null; }
                    public FieldVisitor visitField(int ac,String n,String d,String s,Object v){ fields.computeIfAbsent(cn,k->new HashSet<>()).add(n+":"+d); return null; }
                }, ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); } catch (Throwable t) {}
            }
        }
    }

    static boolean hasMethod(String owner, String name, String desc) {
        for (String c = owner; c != null; c = supr.get(c)) {
            Set<String> s = methods.get(c); if (s == null) continue;
            for (String m : s) if (m.startsWith(name+"(") && (desc == null || m.equals(name+desc))) return true;
        }
        return false;
    }
    /** Mixin resolves @Inject/@Redirect/@At selectors against the target class itself, not its
     *  supertypes, so a method only inherited by the target does NOT satisfy a selector. */
    static boolean declaresMethod(String owner, String name, String desc) {
        Set<String> s = methods.get(owner); if (s == null) return false;
        for (String m : s) if (m.startsWith(name+"(") && (desc == null || m.equals(name+desc))) return true;
        return false;
    }

    static boolean hasField(String owner, String name) {
        for (String c = owner; c != null; c = supr.get(c)) {
            Set<String> s = fields.get(c); if (s == null) continue;
            for (String f : s) if (f.startsWith(name+":")) return true;
        }
        return false;
    }

    /** static-final String constants declared by the mixin itself (mixin selectors are sometimes a
     *  named constant rather than a literal, which a literal-only scan silently misses). */
    static Map<String,String> constants(byte[] b) {
        Map<String,String> out = new HashMap<>();
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            public MethodVisitor visitMethod(int ac, String n, String d, String s, String[] ex) {
                if (!n.equals("<clinit>")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    String pending;
                    public void visitLdcInsn(Object v) { if (v instanceof String) pending = (String) v; }
                    public void visitFieldInsn(int op, String o, String fn, String fd) {
                        if (op == Opcodes.PUTSTATIC && pending != null) { out.put(fn, pending); pending = null; }
                    }
                };
            }
        }, 0);
        return out;
    }

    static void audit(byte[] b, String rel) {
        final String[] target = new String[1];
        final List<String> found = new ArrayList<>();
        consts = constants(b);
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            public AnnotationVisitor visitAnnotation(String d, boolean vis) {
                if (!d.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    public AnnotationVisitor visitArray(String n) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            public void visit(String k, Object v) { if (v instanceof Type) target[0] = ((Type) v).getInternalName(); }
                        };
                    }
                };
            }
            public MethodVisitor visitMethod(int ac, String mname, String mdesc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    public AnnotationVisitor visitAnnotation(String d, boolean vis) {
                        boolean isAccessor = d.endsWith("/gen/Accessor;"), isInvoker = d.endsWith("/gen/Invoker;");
                        boolean isShadow = d.equals("Lorg/spongepowered/asm/mixin/Shadow;");
                        boolean isInject = d.startsWith("Lorg/spongepowered/asm/mixin/injection/") || d.startsWith("Lcom/llamalad7/");
                        if (!isAccessor && !isInvoker && !isShadow && !isInject) return null;
                        return new AV(isAccessor, isInvoker, isShadow, mname, target, found);
                    }
                };
            }
            public FieldVisitor visitField(int ac, String fname, String fdesc, String sig, Object v) {
                return new FieldVisitor(Opcodes.ASM9) {
                    public AnnotationVisitor visitAnnotation(String d, boolean vis) {
                        if (!d.equals("Lorg/spongepowered/asm/mixin/Shadow;")) return null;
                        if (target[0] != null && !hasField(target[0], fname))
                            fail(rel + "  @Shadow field " + fname + " -> NOT FOUND in " + target[0].replace('/','.'));
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);

        if (target[0] != null && !classes.contains(target[0]))
            fail(rel + "  @Mixin target class MISSING: " + target[0].replace('/','.'));

        for (String sel : found) {
            if (target[0] == null) continue;
            String nm = sel, dsc = null;
            int p = sel.indexOf('(');
            if (p >= 0) { nm = sel.substring(0, p); dsc = sel.substring(p); }
            if (!declaresMethod(target[0], nm, dsc))
                fail(rel + "  method selector \"" + sel + "\" -> NOT DECLARED by " + target[0].replace('/','.')
                     + (hasMethod(target[0], nm, dsc) ? "  (inherited only - Mixin will not resolve it)" : ""));
        }
    }

    /** Collects method selectors, @At targets and accessor/invoker/shadow names recursively. */
    static class AV extends AnnotationVisitor {
        final boolean acc, inv, shadow; final String m; final String[] target; final List<String> found;
        AV(boolean acc, boolean inv, boolean shadow, String m, String[] target, List<String> found) {
            super(Opcodes.ASM9); this.acc=acc; this.inv=inv; this.shadow=shadow; this.m=m; this.target=target; this.found=found;
        }
        public void visit(String n, Object v) {
            if (n == null) return;
            if ((acc || inv) && n.equals("value") && v instanceof String) {
                String t = (String) v;
                boolean ok = acc ? hasField(target[0], t) : hasMethod(target[0], t, null);
                if (target[0] != null && !ok) fail(rel() + "  @" + (acc?"Accessor":"Invoker") + "(" + t + ") on " + m + " -> NOT FOUND in " + target[0].replace('/','.'));
            }
            if (shadow && n.equals("value") && v instanceof String) {
                String t = (String) v;
                if (target[0] != null && !hasMethod(target[0], t, null))
                    fail(rel() + "  @Shadow method \"" + t + "\" -> NOT FOUND in " + target[0].replace('/','.'));
            }
            if (n.equals("method")) {
                if (v instanceof String) found.add(consts.getOrDefault((String) v, (String) v));
                else if (v instanceof String[]) { String[] s = (String[]) v; String nm = s.length > 1 ? s[0]+s[1] : s[0]; found.add(consts.getOrDefault(nm, nm)); }
            }
            if (n.equals("target") && v instanceof String) {
                String t = (String) v;
                if (t.startsWith("L") && t.indexOf(';') > 0) {
                    int semi = t.indexOf(';'); String owner = t.substring(1, semi); String rest = t.substring(semi+1);
                    if (!classes.contains(owner)) fail(rel() + "  @At target owner MISSING: " + owner.replace('/','.'));
                    else {
                        int pr = rest.indexOf('(');
                        String rn = pr < 0 ? rest : rest.substring(0, pr);
                        String rd = pr < 0 ? null : rest.substring(pr, rest.indexOf(')')+1);
                        if (!declaresMethod(owner, rn, rd)) fail(rel() + "  @At target member NOT DECLARED by " + owner.replace('/','.') + "." + rn + (rd==null?"":rd)
                             + (hasMethod(owner, rn, rd) ? "  (inherited only)" : ""));
                    }
                }
            }
        }
        public AnnotationVisitor visitAnnotation(String n, Object v) { return this; }
        public AnnotationVisitor visitArray(String n) { return this; }
        String rel() { return ctx; }
    }
    static String ctx = "";
    static Map<String,String> consts = new HashMap<>();
    static void fail(String s) { problems++; System.out.println("PROBLEM " + s); }
}
