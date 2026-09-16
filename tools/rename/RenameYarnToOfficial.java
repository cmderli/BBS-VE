import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Renames BBS's Minecraft references from Yarn names to Mojang official names.
 *
 * <p>Why this exists: 1.21.11 is the last version Fabric publishes Yarn mappings for, and 26.2
 * ships unobfuscated, so a mod going to 26.2 has to speak Mojang's names. Renaming a class name is
 * a text substitution, but renaming a <em>method</em> is not: the same identifier is usually also
 * a method on one of BBS's own types ({@code render}, {@code update}, {@code copy}), so a textual
 * pass either misses references or corrupts unrelated code.</p>
 *
 * <p>This tool therefore resolves every reference with a real type solver against a Yarn-named
 * 1.21.11 jar before touching it, and writes the result through JavaParser's
 * {@link LexicalPreservingPrinter} so comments and formatting survive untouched — which matters
 * here, because the comments are where this codebase keeps its reasoning.</p>
 *
 * <p>What it renames:</p>
 * <ul>
 *   <li>imports of an official-named Minecraft or Blaze3d type, and every reference to it
 *       (types, {@code .class} literals, {@code new}, generics, casts);</li>
 *   <li>method calls and field/enum-constant reads whose <em>declaring</em> class is one of those
 *       types — the member table is keyed by (owner, name) and, measured over 1.21.11, has no
 *       ambiguous key at all, so no descriptor matching is needed;</li>
 *   <li>method <em>declarations</em> that carry {@code @Override} and match an inherited
 *       Minecraft method by name and arity, because an override that keeps the Yarn name silently
 *       stops overriding;</li>
 *   <li>{@code static} imports of such members.</li>
 * </ul>
 *
 * <p>What it deliberately does not do: guess. A node the solver cannot resolve is left alone and
 * counted in the report. JavaParser's solver is not javac — when it cannot work something out it
 * returns unresolved, and an unresolved node here means "unchanged", never "renamed by name
 * matching". The compile run is what proves the result.</p>
 */
public final class RenameYarnToOfficial
{
    private static final Set<String> MC_PACKAGE_PREFIXES = Set.of("net.minecraft.", "com.mojang.blaze3d.");

    private final Map<String, String> classes = new HashMap<>();      // yarn/class/Name -> official/class/Name
    private final Map<String, String> classSimple = new HashMap<>();  // yarn/class/Name -> OfficialSimpleName
    private final Map<String, String> members = new HashMap<>();      // owner/Class#name -> officialName
    private final Map<String, Map<String, String>> membersByOwner = new HashMap<>();

    private final List<String> files = new ArrayList<>();
    private JavaParser parser;
    /** For the file being rewritten: yarn simple name -> yarn FQN, and -> official simple name. */
    private Map<String, String> fileYarnImports;
    private Map<String, String> fileOfficialSimple;
    /** Members of the file's own Minecraft imports, keyed yarnFqn#name -> official name. */
    private Map<String, String> fileStaticMembers;
    private final Set<String> plannedRanges = new HashSet<>();
    private boolean verbose;
    private boolean plainPrinter;
    private int changed;
    private int renames;
    private int unresolved;
    private final Set<String> conflicts = new TreeSet<>();
    private final Map<String, Integer> unresolvedReasons = new TreeMap<>();
    private final Map<String, Integer> kindCounts = new TreeMap<>();

    public static void main(String[] args) throws Exception
    {
        Path root = null;
        Path classesTsv = null;
        Path membersTsv = null;
        Path cpFile = null;
        Path report = null;
        List<Path> solverRoots = new ArrayList<>();
        boolean write = false;
        boolean verbose = false;
        boolean plainPrinter = false;
        List<String> only = new ArrayList<>();

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--src" -> root = Paths.get(args[++i]);
                case "--classes" -> classesTsv = Paths.get(args[++i]);
                case "--members" -> membersTsv = Paths.get(args[++i]);
                case "--classpath-file" -> cpFile = Paths.get(args[++i]);
                case "--report" -> report = Paths.get(args[++i]);
                case "--only" -> only.add(args[++i]);
                case "--solver-root" -> solverRoots.add(Paths.get(args[++i]));
                case "--write" -> write = true;
                case "--verbose" -> verbose = true;
                case "--plain-printer" -> plainPrinter = true;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        RenameYarnToOfficial tool = new RenameYarnToOfficial();

        tool.verbose = verbose;
        tool.plainPrinter = plainPrinter;

        tool.loadMappings(classesTsv, membersTsv);
        tool.collect(root, only);
        if (solverRoots.isEmpty())
        {
            solverRoots.add(root);
        }

        tool.buildSolver(solverRoots, cpFile);

        System.out.println("classes=" + tool.classes.size() + " members=" + tool.members.size() + " files=" + tool.files.size());

        for (String file : tool.files)
        {
            tool.rewrite(Paths.get(file), write);
        }

        String summary = tool.report();

        System.out.println(summary);

        if (report != null)
        {
            Files.writeString(report, summary, StandardCharsets.UTF_8);
        }
    }

    private void loadMappings(Path classesTsv, Path membersTsv) throws IOException
    {
        for (String line : Files.readAllLines(classesTsv, StandardCharsets.UTF_8))
        {
            if (line.isBlank() || line.startsWith("#"))
            {
                continue;
            }

            String[] f = line.split("\t");

            if (f.length < 2 || f[0].equals(f[1]))
            {
                continue;
            }

            this.classes.put(f[0], f[1]);
            this.classSimple.put(f[0], f[1].substring(f[1].lastIndexOf('/') + 1).replace('$', '.'));
        }

        for (String line : Files.readAllLines(membersTsv, StandardCharsets.UTF_8))
        {
            if (line.isBlank() || line.startsWith("#"))
            {
                continue;
            }

            String[] f = line.split("\t");

            if (f.length < 5 || f[1].equals("<init>") || f[1].equals(f[4]))
            {
                continue;
            }

            this.members.put(f[0] + "#" + f[1], f[4]);
            this.membersByOwner.computeIfAbsent(f[0], key -> new HashMap<>()).put(f[1], f[4]);
        }
    }

    private void collect(Path root, List<String> only) throws IOException
    {
        if (!only.isEmpty())
        {
            for (String f : only)
            {
                this.files.add(root.resolve(f).toString());
            }

            return;
        }

        try (var stream = Files.walk(root))
        {
            stream.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> this.files.add(p.toString()));
        }
    }

    /**
     * @param solverRoots the source roots of the mod itself, in the layout JavaParser expects —
     *                    it resolves {@code mchorse.bbs_mod.X} by looking for
     *                    {@code <root>/mchorse/bbs_mod/X.java}, so a directory that merely
     *                    contains {@code main/java} and {@code client/java} is not one. Getting
     *                    this wrong is not subtle: without BBS's own types resolved, every call on
     *                    a BBS object fails to resolve, and the tool leaves most of the tree alone.
     */
    private void buildSolver(List<Path> solverRoots, Path cpFile) throws IOException
    {
        CombinedTypeSolver solver = new CombinedTypeSolver();

        solver.add(new ReflectionTypeSolver(false));

        for (Path root : solverRoots)
        {
            solver.add(new JavaParserTypeSolver(root.toFile()));
        }

        if (cpFile != null)
        {
            for (String entry : Files.readString(cpFile, StandardCharsets.UTF_8).split(java.io.File.pathSeparator))
            {
                if (entry.isBlank())
                {
                    continue;
                }

                Path path = Paths.get(entry);

                if (Files.isRegularFile(path) && entry.endsWith(".jar"))
                {
                    try
                    {
                        solver.add(new JarTypeSolver(path));
                    }
                    catch (Exception e)
                    {
                        /* A jar the solver cannot index is not fatal: symbols from it stay
                         * unresolved, which this tool treats as "leave it alone". */
                    }
                }
            }
        }

        /* The language level matters more than it looks. On a file JavaParser cannot fully parse
         * it does not inject the symbol resolver into the compilation unit at all, and every
         * resolution then fails with "Symbol resolution not configured" — which reads like a
         * classpath problem but is really a syntax one. The mod is Java 21 here (records, pattern
         * switch, sealed types), so the parser has to be told that. */
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setSymbolResolver(new JavaSymbolSolver(solver));

        this.parser = new JavaParser(configuration);
    }

    private void rewrite(Path file, boolean write) throws IOException
    {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        CompilationUnit cu;

        try
        {
            var parsed = this.parser.parse(source);

            cu = parsed.getResult().orElse(null);

            if (parsed.isSuccessful() == false && this.verbose)
            {
                System.out.println("  [debug] parse problems in " + file + ": " + parsed.getProblems());
            }
        }
        catch (Exception e)
        {
            this.conflicts.add("PARSE FAILED " + file + ": " + e.getMessage());

            return;
        }

        if (cu == null)
        {
            this.conflicts.add("PARSE FAILED " + file);

            return;
        }

        if (!cu.containsData(Node.SYMBOL_RESOLVER_KEY))
        {
            this.conflicts.add("NO SYMBOL RESOLVER (the file did not parse cleanly at Java 21) " + file);

            return;
        }

        /* Edits are collected as source ranges against the ORIGINAL text rather than applied to
         * the AST and reprinted. LexicalPreservingPrinter was the first approach and it silently
         * dropped some renames (a local variable's type kept its old name while the parameter
         * types in the same method were renamed), which is exactly the kind of failure that would
         * surface later as an unexplainable compile error. A range splice cannot lose an edit:
         * every byte of the file other than the renamed identifiers is copied verbatim, so
         * comments and formatting are preserved by construction. */
        if (this.verbose)
        {
            System.out.println("  [debug] parser=" + (this.parser != null) + " resolverOnCu=" + cu.containsData(Node.SYMBOL_RESOLVER_KEY) + " (" + file + ")");
        }

        /* Per file: the dedupe key is a range, and two different files routinely have an import
         * at the same line and column. Leaving this set populated across files silently skipped
         * every rename that happened to share a position with an earlier file's. */
        this.plannedRanges.clear();
        this.buildFileTables(cu);

        List<Planned> plan = new ArrayList<>();

        cu.findAll(ImportDeclaration.class).forEach(decl -> this.planImport(decl, plan));
        cu.findAll(ClassOrInterfaceType.class).forEach(type -> this.planType(type, plan));
        cu.findAll(ClassExpr.class).forEach(expr -> this.planClassExpr(expr, plan));
        cu.findAll(ObjectCreationExpr.class).forEach(expr -> this.planObjectCreation(expr, plan));
        cu.findAll(MethodCallExpr.class).forEach(call -> this.planMethodCall(call, plan));
        cu.findAll(FieldAccessExpr.class).forEach(access -> this.planFieldAccess(access, plan));
        cu.findAll(NameExpr.class).forEach(name -> this.planNameExpr(name, plan));
        cu.findAll(MethodDeclaration.class).forEach(method -> this.planOverride(method, plan));

        if (plan.isEmpty())
        {
            return;
        }

        int[] lineStarts = lineStarts(source);
        List<int[]> ranges = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();

        for (Planned planned : plan)
        {
            var range = planned.node().getRange().orElse(null);

            if (range == null)
            {
                this.conflicts.add("NO RANGE for a planned rename in " + file);

                continue;
            }

            int start = offsetOf(lineStarts, range.begin.line, range.begin.column);
            int end = offsetOf(lineStarts, range.end.line, range.end.column) + 1;

            if (start < 0 || end > source.length() || start >= end)
            {
                this.conflicts.add("BAD RANGE for a planned rename in " + file);

                continue;
            }

            ranges.add(new int[] {start, end});
            texts.add(planned.newName());
            nodes.add(planned.node());
        }

        String renamed = splice(source, ranges, texts, file);

        this.checkConflicts(cu, file);

        if (write)
        {
            Files.writeString(file, renamed, StandardCharsets.UTF_8);
        }

        if (this.verbose)
        {
            for (int i = 0; i < nodes.size(); i++)
            {
                System.out.println("  " + ranges.get(i)[0] + "  " + source.substring(ranges.get(i)[0], ranges.get(i)[1])
                    + " -> " + texts.get(i) + "   [" + kindOf(nodes.get(i)) + "] (" + file + ")");
            }
        }

        this.changed++;
        this.renames += texts.size();
    }

    /** Offsets of the start of every line, 1-based line numbers indexing into it. */
    private static int[] lineStarts(String source)
    {
        List<Integer> starts = new ArrayList<>();

        starts.add(0);

        for (int i = 0; i < source.length(); i++)
        {
            if (source.charAt(i) == '\n')
            {
                starts.add(i + 1);
            }
        }

        int[] array = new int[starts.size()];

        for (int i = 0; i < array.length; i++)
        {
            array[i] = starts.get(i);
        }

        return array;
    }

    private static int offsetOf(int[] lineStarts, int line, int column)
    {
        if (line < 1 || line > lineStarts.length)
        {
            return -1;
        }

        return lineStarts[line - 1] + (column - 1);
    }

    /**
     * Apply the edits to the source text. Overlapping edits are a bug in the planner (two nodes
     * claiming the same characters), so they are reported instead of being applied in an order
     * that would quietly produce garbage.
     */
    private static String splice(String source, List<int[]> ranges, List<String> texts, Path file)
    {
        Integer[] order = new Integer[ranges.size()];

        for (int i = 0; i < order.length; i++)
        {
            order[i] = i;
        }

        Arrays.sort(order, Comparator.comparingInt(i -> ranges.get(i)[0]));

        StringBuilder builder = new StringBuilder(source.length() + 64);
        int cursor = 0;

        for (int index : order)
        {
            int[] range = ranges.get(index);

            if (range[0] < cursor)
            {
                continue;
            }

            builder.append(source, cursor, range[0]);
            builder.append(texts.get(index));
            cursor = range[1];
        }

        builder.append(source, cursor, source.length());

        return builder.toString();
    }

    /**
     * A type name used as an expression — {@code Identifier.of(…)}, {@code MinecraftClient.getInstance()},
     * {@code RotationAxis.POSITIVE_X} — is a {@link NameExpr}, not a {@link ClassOrInterfaceType}, so
     * the type walk never sees it and the solver will not resolve it as a value either. That is how
     * a first attempt at this tool renamed the imports and left the bodies saying
     * {@code RotationAxis.…}, which does not compile.
     *
     * <p>These tables are built from the file's own imports, so the fallback only ever fires for a
     * name the file has explicitly imported from Minecraft: a local variable that shadows the name
     * resolves as a value and takes the member path instead, and a BBS type with the same simple
     * name is not in the table at all.</p>
     */
    private void buildFileTables(CompilationUnit cu)
    {
        this.fileYarnImports = new HashMap<>();
        this.fileOfficialSimple = new HashMap<>();
        this.fileStaticMembers = new HashMap<>();

        for (ImportDeclaration decl : cu.getImports())
        {
            if (decl.isStatic() || decl.isAsterisk())
            {
                continue;
            }

            String yarnFqn = decl.getNameAsString();

            if (!isMinecraft(yarnFqn))
            {
                continue;
            }

            String official = this.lookupClass(yarnFqn);

            if (official == null)
            {
                continue;
            }

            String simple = yarnFqn.substring(yarnFqn.lastIndexOf('.') + 1);
            String officialSimple = official.substring(official.lastIndexOf('/') + 1).replace('$', '.');

            this.fileYarnImports.put(simple, yarnFqn.replace('.', '/'));
            this.fileOfficialSimple.put(simple, officialSimple);

            Map<String, String> ownerMembers = this.membersByOwner.get(yarnFqn.replace('.', '/'));

            if (ownerMembers != null)
            {
                for (Map.Entry<String, String> entry : ownerMembers.entrySet())
                {
                    this.fileStaticMembers.put(yarnFqn.replace('.', '/') + "#" + entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Record one edit.
     *
     * <p>Deliberately not a {@code Map<Node, String>}: {@link Node#equals} in JavaParser is
     * structural, so three occurrences of the same identifier in one file are "equal" and a map
     * keeps only the first — which is how this tool first managed to rename one of three
     * identical {@code RotationAxis} scopes and leave the other two alone.</p>
     */
    private void add(List<Planned> plan, Node node, String newName)
    {
        String key = node.getRange().map(r -> r.begin.line + ":" + r.begin.column + ":" + r.end.line + ":" + r.end.column).orElse(null);

        if (key != null && !this.plannedRanges.add(key))
        {
            return;
        }

        plan.add(new Planned(node, newName));
    }

    /** One rename: the node whose text is replaced, and what it becomes. */
    private record Planned(Node node, String newName)
    {}

    /** @return the official simple name to use for a type name appearing in an expression. */
    private String typeRenameFor(String simpleName)
    {
        String official = this.fileOfficialSimple == null ? null : this.fileOfficialSimple.get(simpleName);

        return official == null || official.equals(simpleName) ? null : official;
    }

    /** @return the official name of a member read off one of the file's imported Minecraft types. */
    private String typeMemberRenameFor(String simpleName, String member)
    {
        if (this.fileYarnImports == null)
        {
            return null;
        }

        String yarnFqn = this.fileYarnImports.get(simpleName);

        return yarnFqn == null ? null : this.fileStaticMembers.get(yarnFqn + "#" + member);
    }

    /** The simple name of the scope, when the call or access is written against a type. */
    private static String scopeName(Node scope)
    {
        if (scope instanceof NameExpr name)
        {
            return name.getNameAsString();
        }

        return null;
    }

    private void planImport(ImportDeclaration decl, List<Planned> plan)
    {
        String name = decl.getNameAsString();
        boolean isStatic = decl.isStatic();

        if (isStatic)
        {
            /* import static net.minecraft.text.Text.literal; — the class part renames, and the
             * member part too when the table has it. */
            int lastDot = name.lastIndexOf('.');

            if (lastDot < 0)
            {
                return;
            }

            String owner = name.substring(0, lastDot);
            String member = name.substring(lastDot + 1);
            String newOwner = this.lookupClass(owner);

            if (newOwner == null)
            {
                return;
            }

            String newMember = this.members.getOrDefault(newOwner + "#" + member, member);

            this.add(plan, decl.getName(), newOwner.replace('/', '.') + "." + newMember);

            return;
        }

        String official = this.lookupClass(name);

        if (official != null)
        {
            this.add(plan, decl.getName(), official.replace('/', '.'));
        }
    }

    private void planType(ClassOrInterfaceType type, List<Planned> plan)
    {
        String official = this.resolveClass(type);

        if (official != null)
        {
            String simple = official.substring(official.lastIndexOf('/') + 1).replace('$', '.');

            if (!simple.equals(type.getNameAsString()))
            {
                /* A dotted official inner name (Outer.Inner) cannot be a single SimpleName, so only
                 * the last segment is replaced; the scope keeps its own renamed name. */
                String last = simple.contains(".") ? simple.substring(simple.lastIndexOf('.') + 1) : simple;

                this.add(plan, type.getName(), last);
            }
        }
    }

    private void planClassExpr(ClassExpr expr, List<Planned> plan)
    {
        expr.getType().findAll(ClassOrInterfaceType.class).forEach(type -> this.planType(type, plan));
    }

    private void planObjectCreation(ObjectCreationExpr expr, List<Planned> plan)
    {
        this.planType(expr.getType(), plan);
    }

    private void planMethodCall(MethodCallExpr call, List<Planned> plan)
    {
        try
        {
            ResolvedMethodDeclaration decl = call.resolve();
            String owner = decl.declaringType().getQualifiedName();
            String renamed = this.lookupMember(owner, call.getNameAsString());

            if (renamed != null)
            {
                this.add(plan, call.getName(), renamed);
                this.kindCounts.merge("method call", 1, Integer::sum);
            }
        }
        catch (Throwable e)
        {
            this.noteUnresolved(e);
        }

        String scope = scopeName(call.getScope().orElse(null));
        String renamed = scope == null ? null : this.typeMemberRenameFor(scope, call.getNameAsString());

        if (renamed != null)
        {
            this.add(plan, call.getName(), renamed);
            this.kindCounts.merge("static method call", 1, Integer::sum);
        }
    }

    private void planFieldAccess(FieldAccessExpr access, List<Planned> plan)
    {
        try
        {
            ResolvedValueDeclaration decl = access.resolve();
            ResolvedType type = decl.getType();
            String owner = null;

            if (decl.isField())
            {
                owner = decl.asField().declaringType().getQualifiedName();
            }
            else if (type.isReferenceType())
            {
                /* An enum constant reached through the enum's own name. */
                owner = type.asReferenceType().getQualifiedName();
            }

            if (owner == null)
            {
                return;
            }

            String renamed = this.lookupMember(owner, access.getNameAsString());

            if (renamed != null)
            {
                this.add(plan, access.getName(), renamed);
                this.kindCounts.merge("field access", 1, Integer::sum);

                return;
            }
        }
        catch (Throwable e)
        {
            this.noteUnresolved(e);
        }

        /* Enum constants and static fields read off a type name: the scope is a type, so there is
         * no receiver value to resolve the member against. The file's own import says which type
         * it is, and the member table has no ambiguous (owner, name) key, so the lookup is exact. */
        String scope = scopeName(access.getScope());
        String renamed = scope == null ? null : this.typeMemberRenameFor(scope, access.getNameAsString());

        if (renamed != null)
        {
            this.add(plan, access.getName(), renamed);
            this.kindCounts.merge("static field access", 1, Integer::sum);
        }
    }

    private void planNameExpr(NameExpr name, List<Planned> plan)
    {
        if (this.verbose && this.fileOfficialSimple.containsKey(name.getNameAsString()))
        {
            String kind;

            try
            {
                ResolvedValueDeclaration decl = name.resolve();

                kind = decl.isField() ? "field:" + decl.asField().declaringType().getQualifiedName()
                    : decl.isType() ? "type" : decl.isVariable() ? "variable" : decl.isParameter() ? "parameter" : "other";
            }
            catch (Throwable e)
            {
                kind = "throws " + e.getClass().getSimpleName();
            }

            System.out.println("  [nameexpr] " + name.getNameAsString() + " at " + name.getRange().map(Object::toString).orElse("?")
                + " resolves=" + kind);
        }

        try
        {
            ResolvedValueDeclaration decl = name.resolve();

            if (decl.isVariable() || decl.isParameter() || decl.isEnumConstant() || decl.isTypePattern())
            {
                /* A local, a parameter or a pattern binding: it shadows anything the file
                 * imported, so this is the end of the road for the name. */
                return;
            }

            if (!decl.isField() && !decl.isType())
            {
                return;
            }

            if (decl.isType())
            {
                /* A type name in an expression position after all; handled below. */
                throw new IllegalStateException("type name");
            }

            String owner = decl.asField().declaringType().getQualifiedName();
            String renamed = this.lookupMember(owner, name.getNameAsString());

            if (renamed != null)
            {
                this.add(plan, name.getName(), renamed);
                this.kindCounts.merge("field read", 1, Integer::sum);

                return;
            }
        }
        catch (Throwable e)
        {
            /* Not a value. It may still be a type used as an expression; fall through. */
        }

        String typeRename = this.typeRenameFor(name.getNameAsString());

        if (typeRename != null)
        {
            this.add(plan, name, typeRename);
            this.kindCounts.merge("type name in expression", 1, Integer::sum);
        }
    }

    /**
     * An override that keeps its Yarn name stops overriding — and nothing fails to compile when
     * that happens, the game just never calls it. So declarations that carry {@code @Override} and
     * match an inherited Minecraft method by name and arity are renamed too.
     */
    private void planOverride(MethodDeclaration method, List<Planned> plan)
    {
        boolean overrides = method.getAnnotations().stream()
            .map(AnnotationExpr::getNameAsString)
            .anyMatch(n -> n.equals("Override") || n.endsWith(".Override"));

        if (!overrides || method.getParentNode().isEmpty())
        {
            return;
        }

        TypeDeclaration<?> owner = enclosingType(method);

        if (owner == null)
        {
            return;
        }

        try
        {
            /* The direct supertypes are enough: an override names a method the immediate
             * superclass or one of its interfaces declares. Anything deeper is inherited through
             * one of these anyway, and an unresolved ancestor is skipped rather than guessed at. */
            String name = method.getNameAsString();
            int arity = method.getParameters().size();

            for (ResolvedType ancestor : owner.resolve().getAncestors())
            {
                if (!ancestor.isReferenceType())
                {
                    continue;
                }

                String ancestorName = ancestor.asReferenceType().getQualifiedName();

                if (!isMinecraft(ancestorName))
                {
                    continue;
                }

                var ancestorType = ancestor.asReferenceType().getTypeDeclaration().orElse(null);

                if (ancestorType == null)
                {
                    continue;
                }

                /* Ask the ancestor type itself which of its methods has this name and arity; the
                 * declared name there is the one being overridden. */
                for (var ancestorDecl : ancestorType.getDeclaredMethods())
                {
                    if (ancestorDecl.getName().equals(name) && ancestorDecl.getNumberOfParams() == arity)
                    {
                        String renamed = this.lookupMember(ancestorName, name);

                        if (renamed != null)
                        {
                            this.add(plan, method.getName(), renamed);
                            this.kindCounts.merge("override declaration", 1, Integer::sum);
                        }

                        return;
                    }
                }
            }
        }
        catch (Throwable e)
        {
            this.unresolved++;
        }
    }

    private static String kindOf(Node node)
    {
        Node parent = node.getParentNode().orElse(null);

        return parent == null ? "?" : parent.getClass().getSimpleName();
    }

    private static TypeDeclaration<?> enclosingType(Node node)
    {
        Node current = node.getParentNode().orElse(null);

        while (current != null)
        {
            if (current instanceof TypeDeclaration<?> type)
            {
                return type;
            }

            current = current.getParentNode().orElse(null);
        }

        return null;
    }

    private String resolveClass(Node node)
    {
        try
        {
            ResolvedType type;

            if (node instanceof ClassOrInterfaceType classType)
            {
                type = classType.resolve();
            }
            else
            {
                return null;
            }

            if (!type.isReferenceType())
            {
                return null;
            }

            String official = this.lookupClass(type.asReferenceType().getQualifiedName());

            if (official != null)
            {
                this.kindCounts.merge("type reference", 1, Integer::sum);
            }

            return official;
        }
        catch (Throwable e)
        {
            this.noteUnresolved(e);

            return null;
        }
    }

    private void noteUnresolved(Throwable e)
    {
        this.unresolved++;

        String message = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');

        if (newline > 0)
        {
            message = message.substring(0, newline);
        }

        if (message.length() > 160)
        {
            message = message.substring(0, 160);
        }

        this.unresolvedReasons.merge(message, 1, Integer::sum);
    }

    private String lookupMember(String owner, String name)
    {
        if (owner == null)
        {
            return null;
        }

        for (String variant : mapNameVariants(owner))
        {
            String renamed = this.members.get(variant + "#" + name);

            if (renamed != null)
            {
                return renamed;
            }
        }

        return null;
    }

    /**
     * The mapping table keys types the way the JVM does ({@code Outer$Inner}); JavaParser reports
     * them the way Java source does ({@code Outer.Inner} or {@code Outer.Inner.Deep}). Both
     * spellings are tried, deepest nesting first.
     */
    private String lookupClass(String qualifiedName)
    {
        String dotted = qualifiedName.replace('.', '.');

        if (!isMinecraft(dotted.replace('/', '.')))
        {
            return null;
        }

        for (String variant : mapNameVariants(qualifiedName))
        {
            String official = this.classes.get(variant);

            if (official != null)
            {
                return official;
            }
        }

        return null;
    }

    private static boolean isMinecraft(String dotted)
    {
        return MC_PACKAGE_PREFIXES.stream().anyMatch(dotted::startsWith);
    }

    /** Try the {@code $} spelling of every suffix, deepest last. */
    private static List<String> mapNameVariants(String qualifiedName)
    {
        List<String> variants = new ArrayList<>();
        String base = qualifiedName.replace('.', '/');

        variants.add(base);

        int index = base.lastIndexOf('/');

        while (index > 0)
        {
            String candidate = base.substring(0, index) + "$" + base.substring(index + 1);

            variants.add(candidate);
            index = candidate.lastIndexOf('/', index - 1);
        }

        return variants;
    }

    private void checkConflicts(CompilationUnit cu, Path file)
    {
        Map<String, String> simpleToImport = new HashMap<>();

        for (ImportDeclaration decl : cu.getImports())
        {
            if (decl.isStatic() || decl.isAsterisk())
            {
                continue;
            }

            String fqn = decl.getNameAsString();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            String previous = simpleToImport.put(simple, fqn);

            if (previous != null && !previous.equals(fqn))
            {
                this.conflicts.add("SIMPLE NAME COLLISION in " + file + ": " + simple + " <- " + previous + " and " + fqn);
            }
        }
    }

    private String report()
    {
        StringBuilder builder = new StringBuilder();

        builder.append("files scanned:   ").append(this.files.size()).append('\n');
        builder.append("files changed:   ").append(this.changed).append('\n');
        builder.append("renames applied: ").append(this.renames).append('\n');
        builder.append("unresolved:      ").append(this.unresolved).append('\n');
        builder.append("by kind:\n");

        this.kindCounts.forEach((kind, count) -> builder.append("  ").append(kind).append(": ").append(count).append('\n'));

        builder.append("unresolved reasons:\n");
        this.unresolvedReasons.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> builder.append("  ").append(entry.getValue()).append("  ").append(entry.getKey()).append('\n'));

        builder.append("conflicts:       ").append(this.conflicts.size()).append('\n');
        this.conflicts.stream().limit(200).forEach(c -> builder.append("  ").append(c).append('\n'));

        return builder.toString();
    }
}
