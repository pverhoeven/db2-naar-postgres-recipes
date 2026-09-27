package nl.example.migratie;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveImport;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stemt JPA-entities af op de kolomtypes in de Postgres-changelogs, per kolom:
 * <ul>
 *     <li>kolom is {@code boolean}: {@code @Convert(converter = ...BooleanConverter.class)} weg;</li>
 *     <li>kolom is {@code uuid}: {@code @JdbcTypeCode(SqlTypes.CHAR)}, {@code @JdbcType(CharJdbcType.class)}
 *     en {@code columnDefinition = "CHAR(36)"} weg.</li>
 * </ul>
 * Op kolommen die niet veranderd zijn (bv. smallint) blijft alles staan. Afwijkingen die om een
 * besluit vragen (Java-type past niet, native SQL met {@code = 1}) komen in de datatabel.
 */
public class StemEntitiesAfOpPostgres extends ScanningRecipe<PostgresSchema> {

    private static final String STANDAARD_MAP = "src/main/resources/db/changelog/postgres";
    private static final Set<String> RELATIES = Set.of("Transient", "OneToMany", "ManyToOne", "OneToOne",
            "ManyToMany", "Embedded", "EmbeddedId", "ElementCollection", "JoinColumn", "JoinColumns");
    private static final Set<String> CHAR_TYPES = Set.of("CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "LONGVARCHAR",
            "LONGNVARCHAR", "LONG32VARCHAR", "LONG32NVARCHAR");
    private static final Pattern CHAR_JDBC_TYPE = Pattern.compile("(Char|Varchar|NChar|NVarchar)JdbcType");
    private static final Set<String> IMPORT_KANDIDATEN = Set.of("Convert", "JdbcTypeCode", "SqlTypes", "JdbcType",
            "CharJdbcType", "VarcharJdbcType", "NCharJdbcType", "NVarcharJdbcType");

    @Option(displayName = "Boolean-converter",
            description = "Reguliere expressie voor de simpele klassenaam van jullie boolean-converter(s).",
            example = ".*Boolean.*Converter", required = false)
    private final String booleanConverter;

    @Option(displayName = "Map met Postgres-changelogs",
            description = "Map (vanaf de projectroot) met de gereviewde Postgres-changelogs.",
            example = STANDAARD_MAP, required = false)
    private final String postgresChangelogMap;

    transient MigratieBevindingen bevindingen = new MigratieBevindingen(this);

    public StemEntitiesAfOpPostgres() {
        this(null, null);
    }

    @JsonCreator
    public StemEntitiesAfOpPostgres(@JsonProperty("booleanConverter") String booleanConverter,
                                    @JsonProperty("postgresChangelogMap") String postgresChangelogMap) {
        this.booleanConverter = booleanConverter;
        this.postgresChangelogMap = postgresChangelogMap;
    }

    public String getBooleanConverter() {
        return booleanConverter;
    }

    public String getPostgresChangelogMap() {
        return postgresChangelogMap;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StemEntitiesAfOpPostgres r
                && java.util.Objects.equals(booleanConverter, r.booleanConverter)
                && java.util.Objects.equals(postgresChangelogMap, r.postgresChangelogMap);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(booleanConverter, postgresChangelogMap);
    }

    @Override
    public String getDisplayName() {
        return "JPA-entities afstemmen op uuid- en boolean-kolommen in Postgres";
    }

    @Override
    public String getDescription() {
        return "Verwijdert boolean-converters en CHAR-mappings voor uuid, alleen op kolommen die in de "
                + "Postgres-changelogs echt boolean of uuid zijn geworden.";
    }

    private Path pgMap() {
        return Paths.get(postgresChangelogMap == null ? STANDAARD_MAP : postgresChangelogMap);
    }

    private Pattern converterPatroon() {
        return Pattern.compile(booleanConverter == null ? ".*Boolean.*Converter" : booleanConverter);
    }

    @Override
    public PostgresSchema getInitialValue(ExecutionContext ctx) {
        return new PostgresSchema();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(PostgresSchema schema) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof Yaml.Documents docs && docs.getSourcePath().startsWith(pgMap())) {
                    PostgresSchema.verzamel(docs, schema);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(PostgresSchema schema) {
        if (schema.isLeeg()) {
            return TreeVisitor.noop();
        }
        Set<String> booleanKolommen = schema.kolommenVanType("boolean");
        Pattern nativeBoolean = booleanKolommen.isEmpty() ? null : Pattern.compile(
                "(?i)\\b(?:\\w+\\.)?(" + String.join("|", booleanKolommen) + ")\\s*(=|<>|!=)\\s*'?[01]'?(?![\\w.])");
        Pattern converter = converterPatroon();

        return new JavaIsoVisitor<ExecutionContext>() {
            private final Set<String> verwijderdeConverters = new HashSet<>();

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                verwijderdeConverters.clear();
                J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                if (c != cu) {
                    ruimImportsOp(c);
                }
                return c;
            }

            /**
             * Import weg als de simpele naam nergens meer als identifier in de code voorkomt (werkt ook
             * zonder type-informatie). Commentaar telt niet mee.
             */
            private void ruimImportsOp(J.CompilationUnit c) {
                Set<String> gebruikt = new HashSet<>();
                new JavaIsoVisitor<Set<String>>() {
                    @Override
                    public J.Import visitImport(J.Import imp, Set<String> s) {
                        return imp;
                    }

                    @Override
                    public J.Identifier visitIdentifier(J.Identifier id, Set<String> s) {
                        s.add(id.getSimpleName());
                        return super.visitIdentifier(id, s);
                    }
                }.visit(c, gebruikt);
                for (J.Import imp : c.getImports()) {
                    String fqn = imp.getTypeName();
                    String simpel = fqn.substring(fqn.lastIndexOf('.') + 1);
                    if ((IMPORT_KANDIDATEN.contains(simpel) || verwijderdeConverters.contains(simpel))
                            && !gebruikt.contains(simpel)) {
                        doAfterVisit(new RemoveImport<>(fqn, true));
                    }
                }
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, ExecutionContext ctx) {
                if (nativeBoolean != null && literal.getValue() instanceof String sql) {
                    Matcher m = nativeBoolean.matcher(sql);
                    while (m.find()) {
                        meld(ctx, "Handmatig", "`" + m.group() + "` vergelijkt een boolean-kolom met 0/1; wordt "
                                + m.group(1) + " = true/false");
                    }
                }
                return super.visitLiteral(literal, ctx);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVariable, ctx);
                if (getCursor().firstEnclosing(J.MethodDeclaration.class) != null
                        || v.hasModifier(J.Modifier.Type.Static)
                        || v.getVariables().size() != 1) {
                    return v;
                }
                J.ClassDeclaration klasse = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (klasse == null) {
                    return v;
                }
                String veld = klasse.getSimpleName() + "." + v.getVariables().get(0).getSimpleName();
                boolean entity = heeft(klasse.getLeadingAnnotations(), "jakarta.persistence.Entity");
                boolean gedeeld = heeft(klasse.getLeadingAnnotations(), "jakarta.persistence.MappedSuperclass")
                        || heeft(klasse.getLeadingAnnotations(), "jakarta.persistence.Embeddable");
                if (gedeeld && (heeft(v.getLeadingAnnotations(), "jakarta.persistence.Convert")
                        || heeft(v.getLeadingAnnotations(), "org.hibernate.annotations.JdbcTypeCode"))) {
                    meld(ctx, "Handmatig", veld + ": @MappedSuperclass/@Embeddable, tabel onbekend; handmatig nalopen");
                }
                if (!entity || v.getLeadingAnnotations().stream().anyMatch(a -> RELATIES.contains(a.getSimpleName()))) {
                    return v;
                }

                String tabel = tabelNaam(klasse);
                Map<String, String> kolommen = schema.kolommen(tabel);
                if (kolommen.isEmpty()) {
                    return v; // tabel niet in de baseline; wordt één keer per klasse gemeld hieronder
                }
                String kolom = kolomNaam(v);
                String pgType = kolommen.get(kolom);
                String javaType = typeNaam(v.getTypeExpression());
                String waar = veld + " (" + tabel + "." + kolom + ")";
                if (pgType == null) {
                    if (!v.getLeadingAnnotations().isEmpty()) {
                        meld(ctx, "Handmatig", waar + ": kolom niet gevonden in de Postgres-changelog");
                    }
                    return v;
                }

                // boolean-converter
                J.VariableDeclarations resultaat = v;
                boolean converterBlijft = false;
                for (J.Annotation a : v.getLeadingAnnotations()) {
                    if (!isAnnotatie(a, "jakarta.persistence.Convert")) {
                        continue;
                    }
                    String conv = klasseNaam(argument(a, "converter"));
                    if (conv == null || !converter.matcher(conv).matches()) {
                        continue;
                    }
                    if ("boolean".equals(pgType)) {
                        resultaat = verwijder(resultaat, x -> x.getId().equals(a.getId()));
                        verwijderdeConverters.add(conv);
                        meld(ctx, "Aangepast", waar + ": @Convert(" + conv + ") verwijderd, kolom is nu boolean");
                    } else {
                        converterBlijft = true;
                        meld(ctx, "Laten staan", waar + ": @Convert(" + conv + ") blijft, kolom is " + pgType);
                    }
                }

                // CHAR-mapping voor uuid
                if ("uuid".equals(pgType)) {
                    for (J.Annotation a : v.getLeadingAnnotations()) {
                        boolean charCode = isAnnotatie(a, "org.hibernate.annotations.JdbcTypeCode")
                                && CHAR_TYPES.contains(simpeleNaam(eersteArgument(a)));
                        String jdbcType = isAnnotatie(a, "org.hibernate.annotations.JdbcType") ? klasseNaam(eersteArgument(a)) : null;
                        if (charCode || (jdbcType != null && CHAR_JDBC_TYPE.matcher(jdbcType).matches())) {
                            resultaat = verwijder(resultaat, x -> x.getId().equals(a.getId()));
                            meld(ctx, "Aangepast", waar + ": @" + a.getSimpleName() + " (CHAR) verwijderd, kolom is nu uuid");
                        }
                    }
                    resultaat = resultaat.withLeadingAnnotations(ListUtils.map(resultaat.getLeadingAnnotations(), a -> {
                        if (!isAnnotatie(a, "jakarta.persistence.Column") || a.getArguments() == null) {
                            return a;
                        }
                        List<Expression> args = ListUtils.map(a.getArguments(), arg ->
                                arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id
                                        && id.getSimpleName().equals("columnDefinition")
                                        && as.getAssignment() instanceof J.Literal l && l.getValue() instanceof String s
                                        && s.trim().toUpperCase(Locale.ROOT).matches("N?(VAR)?CHAR.*") ? null : arg);
                        if (args.size() == a.getArguments().size()) {
                            return a;
                        }
                        meld(ctx, "Aangepast", waar + ": columnDefinition (CHAR) uit @Column verwijderd, kolom is nu uuid");
                        if (!args.isEmpty()) {
                            args = ListUtils.mapFirst(args, e -> e.withPrefix(Space.EMPTY));
                        }
                        return a.withArguments(args);
                    }));
                }

                // controles
                if ("boolean".equals(pgType) && !javaType.equalsIgnoreCase("boolean")) {
                    meld(ctx, "Handmatig", waar + ": Java-type " + javaType + " op boolean-kolom; type aanpassen naar Boolean");
                }
                if ("uuid".equals(pgType) && !javaType.equals("UUID")) {
                    meld(ctx, "Handmatig", waar + ": Java-type " + javaType + " op uuid-kolom; overweeg UUID");
                }
                if (javaType.equalsIgnoreCase("boolean") && !"boolean".equals(pgType) && !converterBlijft) {
                    meld(ctx, "Handmatig", waar + ": boolean-veld op " + pgType + "-kolom zonder converter");
                }
                return resultaat;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (heeft(classDecl.getLeadingAnnotations(), "jakarta.persistence.Entity")
                        && !schema.kent(tabelNaam(classDecl))) {
                    meld(ctx, "Handmatig", classDecl.getSimpleName() + ": tabel " + tabelNaam(classDecl)
                            + " niet gevonden in de Postgres-changelog; entity niet aangepast");
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }

            private void meld(ExecutionContext ctx, String soort, String melding) {
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(
                        cu == null ? "?" : cu.getSourcePath().toString(), 0, soort, melding));
            }
        };
    }

    // ------------------------------------------------------------------ hulpjes

    /** Verwijdert annotaties en houdt de witruimte netjes (de eerste annotatie heeft geen eigen prefix). */
    static J.VariableDeclarations verwijder(J.VariableDeclarations v, Predicate<J.Annotation> weg) {
        List<J.Annotation> oud = v.getLeadingAnnotations();
        if (oud.stream().noneMatch(weg)) {
            return v;
        }
        List<J.Annotation> nieuw = ListUtils.map(oud, a -> weg.test(a) ? null : a);
        Space eersteprefix = oud.get(0).getPrefix();
        boolean eersteWeg = weg.test(oud.get(0));
        if (eersteWeg && !nieuw.isEmpty()) {
            nieuw = ListUtils.mapFirst(nieuw, a -> a.withPrefix(eersteprefix));
        }
        J.VariableDeclarations r = v.withLeadingAnnotations(nieuw);
        if (eersteWeg && nieuw.isEmpty()) {
            if (!r.getModifiers().isEmpty()) {
                r = r.withModifiers(ListUtils.mapFirst(r.getModifiers(), m -> m.withPrefix(eersteprefix)));
            } else if (r.getTypeExpression() != null) {
                r = r.withTypeExpression(r.getTypeExpression().withPrefix(eersteprefix));
            }
        }
        return r;
    }

    static boolean heeft(List<J.Annotation> annotaties, String fqn) {
        return annotaties.stream().anyMatch(a -> isAnnotatie(a, fqn));
    }

    /** Op type, met terugval op de simpele naam als er geen type-informatie is. */
    static boolean isAnnotatie(J.Annotation a, String fqn) {
        return TypeUtils.isOfClassType(a.getType(), fqn)
                || (a.getType() == null || a.getType() instanceof JavaType.Unknown)
                && a.getSimpleName().equals(fqn.substring(fqn.lastIndexOf('.') + 1));
    }

    static Expression argument(J.Annotation a, String naam) {
        if (a.getArguments() == null) {
            return null;
        }
        for (Expression e : a.getArguments()) {
            if (e instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id && id.getSimpleName().equals(naam)) {
                return as.getAssignment();
            }
        }
        return null;
    }

    static Expression eersteArgument(J.Annotation a) {
        if (a.getArguments() == null || a.getArguments().isEmpty()) {
            return null;
        }
        Expression e = a.getArguments().get(0);
        return e instanceof J.Assignment as ? as.getAssignment() : e;
    }

    static String stringArgument(J.Annotation a, String naam) {
        return argument(a, naam) instanceof J.Literal l && l.getValue() instanceof String s ? s : null;
    }

    /** {@code X.class} of {@code a.b.X.class} -> X. */
    static String klasseNaam(Expression e) {
        if (e instanceof J.FieldAccess fa && fa.getSimpleName().equals("class")) {
            return simpeleNaam(fa.getTarget());
        }
        return null;
    }

    static String simpeleNaam(Expression e) {
        if (e instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        if (e instanceof J.FieldAccess fa) {
            return fa.getSimpleName();
        }
        return null;
    }

    static String typeNaam(TypeTree t) {
        if (t instanceof J.Primitive p) {
            return p.getType().getKeyword();
        }
        String s = simpeleNaam(t instanceof Expression e ? e : null);
        return s == null ? String.valueOf(t) : s;
    }

    static String tabelNaam(J.ClassDeclaration klasse) {
        for (J.Annotation a : klasse.getLeadingAnnotations()) {
            if (isAnnotatie(a, "jakarta.persistence.Table")) {
                String naam = stringArgument(a, "name");
                if (naam != null && !naam.isBlank()) {
                    String n = naam.replace("\"", "");
                    return n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                }
            }
        }
        return snake(klasse.getSimpleName());
    }

    static String kolomNaam(J.VariableDeclarations v) {
        for (J.Annotation a : v.getLeadingAnnotations()) {
            if (isAnnotatie(a, "jakarta.persistence.Column")) {
                String naam = stringArgument(a, "name");
                if (naam != null && !naam.isBlank()) {
                    return naam.replace("\"", "").toLowerCase(Locale.ROOT);
                }
            }
        }
        return snake(v.getVariables().get(0).getSimpleName());
    }

    /** Zoals Spring Boots CamelCaseToUnderscoresNamingStrategy. */
    static String snake(String naam) {
        return naam.replaceAll("(?<=[a-z0-9])([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }
}
