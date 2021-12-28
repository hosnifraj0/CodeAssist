package com.tyron.completion.java.provider;

import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.util.Log;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.MemberReferenceTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.tree.SwitchTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.api.BasicJavacTask;
import org.openjdk.tools.javac.api.JavacTaskImpl;
import org.openjdk.tools.javac.tree.JCTree;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.ParseTask;
import com.tyron.completion.java.rewrite.EditHelper;
import com.tyron.completion.java.util.ElementUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.Name;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.ArrayType;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.PrimitiveType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.type.TypeVariable;
import org.openjdk.javax.lang.model.util.Types;

import me.xdrop.fuzzywuzzy.FuzzySearch;

/**
 * Main entry point for getting completions
 */
@SuppressWarnings("NewApi")
public class CompletionProvider {

    private static final String TAG = CompletionProvider.class.getSimpleName();

    private static final String[] TOP_LEVEL_KEYWORDS = {"package", "import", "public", "private",
            "protected", "abstract", "class", "interface", "@interface", "extends", "implements",};

    private static final String[] CLASS_BODY_KEYWORDS = {"public", "private", "protected",
            "static", "final", "native", "synchronized", "abstract", "default", "class",
            "interface", "void", "boolean", "int", "long", "float", "double",};

    private static final String[] METHOD_BODY_KEYWORDS = {"new", "assert", "try", "catch",
            "finally", "throw", "return", "break", "case", "continue", "default", "do", "while",
            "for", "switch", "if", "else", "instanceof", "final", "class", "void", "boolean",
            "int", "long", "float", "double", "var"};
    //private final JavaParser parser;
    private final JavaCompilerService compiler;

    private static final int MAX_COMPLETION_ITEMS = 50;

    public CompletionProvider(JavaCompilerService compiler) {
        this.compiler = compiler;
    }

    public CompletionList complete(File file, String fileContents, long index) {
        checkCanceled();

        ParseTask task = compiler.parse(file.toPath(), fileContents);
        StringBuilder contents;
        try {
            contents = new PruneMethodBodies(task.task).scan(task.root, index);
            int end = StringSearch.endOfLine(contents, (int) index);
            contents.insert(end, ';');
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Unable to insert semicolon at the end of line, skipping completion", e);
            return new CompletionList();
        }
        String partial = partialIdentifier(contents.toString(), (int) index);
        CompletionList list = compileAndComplete(file, contents.toString(), partial, index);
        list.items = list.items.stream().sorted(Comparator.<CompletionItem>comparingInt(it -> {
            String label = it.label;
            if (label.contains("(")) {
                label = label.substring(0, label.indexOf('('));
            }
            if (label.length() != partial.length()) {
                return FuzzySearch.ratio(label, partial);
            } else {
                return FuzzySearch.partialRatio(label, partial);
            }
        }).reversed()).collect(Collectors.toList());
        return list;
    }

    public CompletionList compileAndComplete(File file, String contents, String partial,
                                             long cursor) {
        SourceFileObject source = new SourceFileObject(file.toPath(), contents, Instant.now());
        boolean endsWithParen = endsWithParen(contents, (int) cursor);

        checkCanceled();
        try (CompileTask task = compiler.compile(Collections.singletonList(source))) {
            TreePath path = new FindCompletionsAt(task.task).scan(task.root(), cursor);
            switch (path.getLeaf().getKind()) {
                case IDENTIFIER:
                    return completeIdentifier(task, path, partial, endsWithParen);
                case MEMBER_SELECT:
                    return completeMemberSelect(task, path, partial, endsWithParen);
                case MEMBER_REFERENCE:
                    return completeMemberReference(task, path, partial);
                case CASE:
                    return completeSwitchConstant(task, path, partial);
                case IMPORT:
                    return completeImport(qualifiedPartialIdentifier(contents, (int) cursor));
                default:
                    CompletionList list = new CompletionList();
                    addKeywords(path, partial, list);
                    return list;
            }
        }
    }

    private void addTopLevelSnippets(ParseTask task, CompletionList list) {
        Path file = Paths.get(task.root.getSourceFile().toUri());
        if (!hasTypeDeclaration(task.root)) {
            list.items.add(classSnippet(file));
            if (task.root.getPackageName() == null) {
                list.items.add(packageSnippet(file));
            }
        }
    }

    private CompletionItem packageSnippet(Path file) {
        String name = "com.tyron.test";
        return snippetItem("package " + name, "package " + name + ";\n\n");
    }

    private CompletionItem classSnippet(Path file) {
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".java".length());
        return snippetItem("class " + name, "class " + name + " {\n    $0\n}");
    }

    private boolean hasTypeDeclaration(CompilationUnitTree root) {
        for (Tree tree : root.getTypeDecls()) {
            if (tree.getKind() != Tree.Kind.ERRONEOUS) {
                return true;
            }
        }
        return false;
    }

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private String qualifiedPartialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    public static boolean isQualifiedIdentifierChar(char c) {
        return c == '.' || Character.isJavaIdentifierPart(c);
    }


    private boolean endsWithParen(String contents, int cursor) {
        for (int i = cursor; i < contents.length(); i++) {
            if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
                return contents.charAt(i) == '(';
            }
        }
        return false;
    }

    private void addKeywords(TreePath path, String partial, CompletionList list) {
        checkCanceled();

        Tree level = findKeywordLevel(path);
        String[] keywords = {};
        if (level instanceof CompilationUnitTree) {
            keywords = TOP_LEVEL_KEYWORDS;
        } else if (level instanceof ClassTree) {
            keywords = CLASS_BODY_KEYWORDS;
        } else if (level instanceof MethodTree) {
            keywords = METHOD_BODY_KEYWORDS;
        }
        for (String k : keywords) {
            if (StringSearch.matchesPartialName(k, partial)) {
                list.items.add(keyword(k));
            }
        }
    }

    private Tree findKeywordLevel(TreePath path) {
        while (path != null) {
            if (path.getLeaf() instanceof CompilationUnitTree || path.getLeaf() instanceof ClassTree || path.getLeaf() instanceof MethodTree) {
                return path.getLeaf();
            }
            path = path.getParentPath();
        }
        throw new RuntimeException("empty path");
    }

    private boolean isAnnotationTree(TreePath path) {
        if (path == null) {
            return false;
        }

        if (path.getLeaf() instanceof JCTree.JCIdent) {
            if (path.getParentPath().getLeaf() instanceof JCTree.JCAnnotation) {
                return true;
            }
        }

        return false;
    }

    private CompletionList completeIdentifier(CompileTask task, TreePath path,
                                              final String partial, boolean endsWithParen) {
        checkCanceled();

        CompletionList list = new CompletionList();
        list.items = completeUsingScope(task, path, partial, endsWithParen);
        if (partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), partial, list);
        }
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);
        addKeywords(path, partial, list);
        return list;
    }

    private CompletionList completeMemberSelect(CompileTask task, TreePath path, String partial,
                                                boolean endsWithParen) {
        checkCanceled();

        MemberSelectTree select = (MemberSelectTree) path.getLeaf();
        path = new TreePath(path, select.getExpression());
        Trees trees = Trees.instance(task.task);
        boolean isStatic = trees.getElement(path) instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);

        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope, (TypeVariable) type, isStatic,
                    partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope, (DeclaredType) type, isStatic,
                    partial, endsWithParen);
        } else if (type instanceof PrimitiveType) {
            return completePrimitiveMemberSelect(task, scope, (PrimitiveType) type, isStatic,
                    partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completePrimitiveMemberSelect(CompileTask task, Scope scope,
                                                         PrimitiveType type, boolean isStatic,
                                                         String partial, boolean endsWithParen) {
        checkCanceled();

        CompletionList list = new CompletionList();
        list.items.add(keyword("class"));
        return list;
    }


    private CompletionList completeArrayMemberSelect(boolean isStatic) {
        checkCanceled();

        if (isStatic) {
            return new CompletionList();
        } else {
            CompletionList list = new CompletionList();
            list.items.add(keyword("length"));
            return list;
        }
    }

    private CompletionList completeTypeVariableMemberSelect(CompileTask task, Scope scope,
                                                            TypeVariable type, boolean isStatic,
                                                            String partial, boolean endsWithParen) {
        checkCanceled();

        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope,
                    (DeclaredType) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope,
                    (TypeVariable) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberSelect(CompileTask task, Scope scope,
                                                            DeclaredType type, boolean isStatic,
                                                            String partial, boolean endsWithParen) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();


        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial) && !partial.endsWith("."))
                continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.addAll(method(overloads, endsWithParen));
        }
        if (isStatic) {
            if (StringSearch.matchesPartialName("class", partial)) {
                list.add(keyword("class"));
            }
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            if (StringSearch.matchesPartialName("this", partial)) {
                list.add(keyword("this"));
            }
            if (StringSearch.matchesPartialName("super", partial)) {
                list.add(keyword("super"));
            }
        }

        CompletionList cl = new CompletionList();
        cl.items = list;
        return cl;
    }


    private List<CompletionItem> completeUsingScope(CompileTask task, TreePath path,
                                                    final String partial, boolean endsWithParen) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        Set<CompletionItem> list = new HashSet<>();
        Scope scope = trees.getScope(path);

        Predicate<CharSequence> filter = p1 -> {
            String label = p1.toString();
            if (label.contains("(")) {
                label = label.substring(0, label.indexOf('('));
            }
            return FuzzySearch.partialRatio(label, partial) > 90;
        };

        if (path.getParentPath().getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
            list.addAll(addLambda(task, path.getParentPath(), partial));
        }

        if (path.getParentPath().getLeaf().getKind() == Tree.Kind.NEW_CLASS) {
            list.addAll(addAnonymous(task, path.getParentPath(), partial));
        }
        for (Element element : ScopeHelper.scopeMembers(task, scope, filter)) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement executableElement = (ExecutableElement) element;
                TreePath parentPath = path.getParentPath().getParentPath();
                Tree parentLeaf = parentPath.getLeaf();
                if (parentLeaf.getKind() == Tree.Kind.CLASS && !ElementUtil.isFinal(executableElement)) {
                    list.addAll(overridableMethod(task, parentPath,
                            Collections.singletonList(executableElement), endsWithParen));
                } else {
                    list.addAll(method(Collections.singletonList(executableElement),
                            endsWithParen));
                }
            } else {
                list.add(item(element));
            }
        }
        return new ArrayList<>(list);
    }

    public List<CompletionItem> addLambda(CompileTask task, TreePath path, String partial) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        List<CompletionItem> items = new ArrayList<>();
        MethodInvocationTree method = (MethodInvocationTree) path.getLeaf();
        Element element = trees.getElement(path);

        if (!(element instanceof ExecutableElement)) {
            return Collections.emptyList();
        }

        ExecutableElement executable = (ExecutableElement) element;

        int argumentToComplete = 0;
        for (int i = 0; i < method.getArguments().size(); i++) {
            ExpressionTree exp = method.getArguments().get(i);
            if (exp.toString().equals(partial) || exp.toString().equals("new " + partial)) {
                argumentToComplete = i;
            }
        }
        List<? extends VariableElement> variableElements = executable.getParameters();

        if (argumentToComplete > variableElements.size() - 1) {
            return Collections.emptyList();
        }

        VariableElement var = variableElements.get(argumentToComplete);

        if (var.asType() instanceof DeclaredType) {
            DeclaredType type = (DeclaredType) var.asType();
            Element classElement = type.asElement();

            if (classElement.getKind() == ElementKind.INTERFACE) {
                Map<String, List<ExecutableElement>> methods = new HashMap<>();

                for (Element enc : classElement.getEnclosedElements()) {
                    if (enc.getKind() == ElementKind.METHOD) {
                        if (enc.getModifiers().contains(Modifier.STATIC)) {
                            continue;
                        }
                        if (enc.getModifiers().contains(Modifier.DEFAULT)) {
                            continue;
                        }
                        putMethod((ExecutableElement) enc, methods);
                    }
                }

                // this is a SAM Interface, suggest a lambda
                if (methods.values().size() == 1) {
                    ExecutableElement sam = methods.values().iterator().next().iterator().next();

                    CompletionItem item = new CompletionItem();

                    StringBuilder label = new StringBuilder();
                    for (VariableElement param : sam.getParameters()) {
                        label.append((label.length() == 0) ? "" : ", ").append(param.getSimpleName());
                    }

                    item.label = (sam.getParameters().size() == 1) ? label + " -> " :
                            "(" + label + ")" + " -> ";
                    item.commitText = item.label;
                    item.detail = EditHelper.printType(sam.getReturnType());
                    item.cursorOffset = item.label.length();
                    item.iconKind = DrawableKind.Lambda;
                    items.add(item);
                }
            }
        }

        return items;
    }

    public List<CompletionItem> addAnonymous(CompileTask task, TreePath path, String partial) {
        checkCanceled();

        List<CompletionItem> items = new ArrayList<>();

        if (!(path.getLeaf() instanceof NewClassTree)) {
            return items;
        }

        if (path.getParentPath().getParentPath().getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
            Trees trees = Trees.instance(task.task);
            MethodInvocationTree method =
                    (MethodInvocationTree) path.getParentPath().getParentPath().getLeaf();
            Element element = trees.getElement(path.getParentPath().getParentPath());

            if (element instanceof ExecutableElement) {
                ExecutableElement executable = (ExecutableElement) element;

                int argumentToComplete = 0;
                for (int i = 0; i < method.getArguments().size(); i++) {
                    ExpressionTree exp = method.getArguments().get(i);
                    if (exp.toString().equals(partial) || exp.toString().equals("new " + partial)) {
                        argumentToComplete = i;
                    }
                }

                VariableElement var = executable.getParameters().get(argumentToComplete);
                if (var.asType() instanceof DeclaredType) {
                    DeclaredType type = (DeclaredType) var.asType();
                    Element classElement = type.asElement();

                    if (StringSearch.matchesPartialName(classElement.getSimpleName().toString(),
                            partial)) {
                        CompletionItem item = new CompletionItem();
                        item.iconKind = DrawableKind.Interface;
                        item.label = classElement.getSimpleName().toString() + " {...}";
                        item.commitText = "" + classElement.getSimpleName() + "() {\n\t//TODO:\n}";
                        item.cursorOffset = item.commitText.length();
                        item.detail = "";

                        if (classElement instanceof TypeElement) {
                            // import the class
                            item.action = CompletionItem.Kind.IMPORT;
                            item.data = ((TypeElement) classElement).getQualifiedName().toString();
                        }
                        items.add(item);
                    }
                }
            }
        }
        return items;
    }

    private void addStaticImports(CompileTask task, CompilationUnitTree root, String partial,
                                  boolean endsWithParen, CompletionList list) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (ImportTree i : root.getImports()) {
            if (!i.isStatic()) continue;
            MemberSelectTree id = (MemberSelectTree) i.getQualifiedIdentifier();
            if (!importMatchesPartial(id.getIdentifier(), partial)) continue;
            TreePath path = trees.getPath(root, id.getExpression());
            TypeElement type = (TypeElement) trees.getElement(path);
            for (Element member : type.getEnclosedElements()) {
                if (!member.getModifiers().contains(Modifier.STATIC)) continue;
                if (!memberMatchesImport(id.getIdentifier(), member)) continue;
                if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
                if (member.getKind() == ElementKind.METHOD) {
                    putMethod((ExecutableElement) member, methods);
                } else {
                    list.items.add(item(member));
                }
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.items.addAll(method(overloads, endsWithParen));
        }
    }

    private boolean isEnclosingClass(DeclaredType type, Scope start) {
        checkCanceled();

        for (Scope s : ScopeHelper.fastScopes(start)) {
            // If we reach a static method, stop looking
            ExecutableElement method = s.getEnclosingMethod();
            if (method != null && method.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
            // If we find the enclosing class
            TypeElement thisElement = s.getEnclosingClass();
            if (thisElement != null && thisElement.asType().equals(type)) {
                return true;
            }
            // If the enclosing class is static, stop looking
            if (thisElement != null && thisElement.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
        }
        return false;
    }

    private boolean importMatchesPartial(Name staticImport, String partial) {
        return staticImport.contentEquals("*") || StringSearch.matchesPartialName(staticImport,
                partial);
    }

    private boolean memberMatchesImport(Name staticImport, Element member) {
        return staticImport.contentEquals("*") || staticImport.contentEquals(member.getSimpleName());
    }

    private CompletionList completeImport(String path) {
        checkCanceled();

        Set<String> names = new HashSet<>();
        CompletionList list = new CompletionList();
        for (String className : compiler.publicTopLevelTypes()) {
            if (className.startsWith(path)) {
                int start = path.lastIndexOf('.');
                int end = className.indexOf('.', path.length());
                if (end == -1) end = className.length();
                String segment = className.substring(start + 1, end);
                if (names.contains(segment)) continue;
                names.add(segment);
                boolean isClass = end == path.length();
                if (isClass) {
                    list.items.add(classItem(className));
                } else {
                    list.items.add(packageItem(segment));
                }
            }
        }
        return list;
    }

    private CompletionList completeMemberReference(CompileTask task, TreePath path,
                                                   String partial) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        MemberReferenceTree select = (MemberReferenceTree) path.getLeaf();
        path = new TreePath(path, select.getQualifierExpression());
        Element element = trees.getElement(path);
        boolean isStatic = element instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);

        if (type instanceof ArrayType) {
            return completeArrayMemberReference(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberReference(task, scope, (TypeVariable) type, isStatic
                    , partial);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(task, scope, (DeclaredType) type, isStatic
                    , partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeArrayMemberReference(boolean isStatic) {
        if (isStatic) {
            CompletionList list = new CompletionList();
            list.items.add(keyword("new"));
            return list;
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeTypeVariableMemberReference(CompileTask task, Scope scope,
                                                               TypeVariable type,
                                                               boolean isStatic, String partial) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(task, scope,
                    (DeclaredType) type.getUpperBound(), isStatic, partial);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberReference(task, scope,
                    (TypeVariable) type.getUpperBound(), isStatic, partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberReference(CompileTask task, Scope scope,
                                                               DeclaredType type,
                                                               boolean isStatic, String partial) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();
        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (!isStatic && member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.addAll(method(overloads, false, true));
        }
        if (isStatic) {
            list.add(keyword("new"));
        }

        CompletionList comp = new CompletionList();
        comp.isIncomplete = !(list.size() > MAX_COMPLETION_ITEMS);
        comp.items = list;
        return comp;
    }

    private CompletionList completeSwitchConstant(CompileTask task, TreePath path, String partial) {
        checkCanceled();

        SwitchTree switchTree = (SwitchTree) path.getLeaf();
        path = new TreePath(path, switchTree.getExpression());
        TypeMirror type = Trees.instance(task.task).getTypeMirror(path);

        if (!(type instanceof DeclaredType)) {
            return new CompletionList();
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        List<CompletionItem> list = new ArrayList<>();
        for (Element member : task.task.getElements().getAllMembers(element)) {
            if (member.getKind() != ElementKind.ENUM_CONSTANT) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            list.add(item(member));
        }

        CompletionList comp = new CompletionList();
        comp.isIncomplete = false;
        comp.items = list;
        return comp;
    }


    private void addClassNames(CompilationUnitTree root, String partial, CompletionList list) {
        checkCanceled();

        String packageName = Objects.toString(root.getPackageName(), "");
        Set<String> uniques = new HashSet<>();
        for (String className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
        for (String className : compiler.publicTopLevelTypes()) {
            if (FuzzySearch.partialRatio(className, partial) < 90) continue;
            if (uniques.contains(className)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
    }

    private void putMethod(ExecutableElement method, Map<String, List<ExecutableElement>> methods) {
        String name = method.getSimpleName().toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        List<ExecutableElement> elements = methods.get(name);
        if (elements != null) {
            elements.add(method);
        }
    }

    private CompletionItem packageItem(String name) {
        CompletionItem item = new CompletionItem();
        item.label = name;
        item.detail = "";
        item.commitText = name;
        item.cursorOffset = name.length();
        item.iconKind = DrawableKind.Package;
        return item;
    }

    private CompletionItem classItem(String className) {
        CompletionItem item = new CompletionItem();
        item.label = simpleName(className).toString();
        item.detail = className;
        item.commitText = item.label;
        item.data = className;
        item.cursorOffset = item.label.length();
        item.action = CompletionItem.Kind.IMPORT;
        item.iconKind = DrawableKind.Class;
        return item;
    }

    private CompletionItem snippetItem(String label, String snippet) {
        CompletionItem item = new CompletionItem();
        item.label = label;
        item.commitText = snippet;
        item.cursorOffset = item.commitText.length();
        item.detail = "Snippet";
        item.iconKind = DrawableKind.Snippet;
        return item;
    }

    private CompletionItem item(Element element) {
        CompletionItem item = new CompletionItem();
        item.label = element.getSimpleName().toString();
        item.detail = simpleType(element.asType());
        item.commitText = element.getSimpleName().toString();
        item.cursorOffset = item.commitText.length();
        item.iconKind = getKind(element);


        return item;
    }

    private CompletionItem keyword(String keyword) {
        CompletionItem item = new CompletionItem();
        item.label = keyword;
        item.commitText = keyword;
        item.cursorOffset = keyword.length();
        item.detail = "keyword";
        item.iconKind = DrawableKind.Keyword;
        return item;
    }

    private List<CompletionItem> overridableMethod(CompileTask task, TreePath parentPath,
                                                   List<ExecutableElement> overloads,
                                                   boolean endsWithParen) {
        checkCanceled();

        List<CompletionItem> items = new ArrayList<>(overloads.size());
        Types types = task.task.getTypes();
        Element parentElement = Trees.instance(task.task).getElement(parentPath);
        DeclaredType type = (DeclaredType) parentElement.asType();
        for (ExecutableElement element : overloads) {
            checkCanceled();

            Element enclosingElement = element.getEnclosingElement();
            if (!types.isAssignable(type, enclosingElement.asType())) {
                items.addAll(method(Collections.singletonList(element), endsWithParen));
                continue;
            }

            ExecutableType executableType = (ExecutableType) types.asMemberOf(type, element);
            CompletionItem item = new CompletionItem();
            item.label = getMethodLabel(element) + getThrowsType(element);
            item.detail = simpleType(element.getReturnType());
            item.commitText = EditHelper.printMethod(element, executableType, element);
            item.cursorOffset = item.commitText.length();
            item.iconKind = DrawableKind.Method;
            items.add(item);
        }
        return items;
    }


    private List<CompletionItem> method(List<ExecutableElement> overloads, boolean endsWithParen) {
        return method(overloads, endsWithParen, false);
    }

    private List<CompletionItem> method(List<ExecutableElement> overloads, boolean endsWithParen,
                                        boolean methodRef) {
        return method(overloads, endsWithParen, methodRef, null);
    }

    private List<CompletionItem> method(List<ExecutableElement> overloads, boolean endsWithParen,
                                        boolean methodRef, DeclaredType type) {
        checkCanceled();
        List<CompletionItem> items = new ArrayList<>();
        for (ExecutableElement first : overloads) {
            checkCanceled();

            CompletionItem item = new CompletionItem();
            item.label = getMethodLabel(first) + getThrowsType(first);
            item.commitText = first.getSimpleName().toString() + ((methodRef || endsWithParen) ?
                    "" : "()");
            item.detail = simpleType(first.getReturnType());
            item.iconKind = DrawableKind.Method;
            item.cursorOffset = item.commitText.length();
            if (first.getParameters() != null && !first.getParameters().isEmpty()) {
                item.cursorOffset = item.commitText.length() - ((methodRef || endsWithParen) ? 0
                        : 1);
            }
            items.add(item);
        }
        return items;
    }

    private String getThrowsType(ExecutableElement e) {
        if (e.getThrownTypes() == null) {
            return "";
        }

        if (e.getThrownTypes().isEmpty()) {
            return "";
        }

        StringBuilder types = new StringBuilder();
        for (TypeMirror m : e.getThrownTypes()) {
            types.append((types.length() == 0) ? "" : ", ").append(simpleType(m));
        }

        return " throws " + types;
    }

    private String simpleType(TypeMirror mirror) {
        return simpleClassName(mirror.toString());
    }

    private String simpleClassName(String name) {
        return name.replaceAll("[a-zA-Z\\.0-9_\\$]+\\.", "");
    }

    private String getMethodLabel(ExecutableElement element) {
        String name = element.getSimpleName().toString();
        StringBuilder params = new StringBuilder();
        for (VariableElement var : element.getParameters()) {
            params.append((params.length() == 0) ? "" : ", ").append(simpleType(var.asType())).append(" ").append(var.getSimpleName());
        }

        return name + "(" + params + ")";
    }

    private CharSequence simpleName(String className) {
        int dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
    }

    private DrawableKind getKind(Element element) {
        switch (element.getKind()) {
            case METHOD:
                return DrawableKind.Method;
            case CLASS:
                return DrawableKind.Class;
            case INTERFACE:
                return DrawableKind.Interface;
            case FIELD:
                return DrawableKind.Filed;
            default:
                return DrawableKind.LocalVariable;
        }
    }
}
