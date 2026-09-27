package nl.example.migratie;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.text.PlainText;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Herstructureert de Liquibase-changelogs voor de overstap van DB2 naar Postgres.
 * <ol>
 *     <li>Alle bestaande bestanden onder de changelog-map verhuizen naar {@code db2/}. Elke changeSet
 *     krijgt {@code dbms: db2} en {@code logicalFilePath} met het OUDE pad, zodat DB2-omgevingen ze
 *     blijven herkennen als uitgevoerd.</li>
 *     <li>De master wordt ter plekke herschreven naar twee includes: {@code db2/} en {@code postgres/}.
 *     De oude inhoud komt in {@code db2/db.changelog-db2.yaml}.</li>
 *     <li>Changesets in {@code postgres/} krijgen {@code dbms: postgresql}; er komt een postgres-master.</li>
 *     <li>Bestanden met "basisdata" in de naam: namen lowercase, 0/1 naar true/false voor
 *     boolean-kolommen, label {@code basisdata}, precondition "tabel is leeg" (anders MARK_RAN),
 *     leesbare ids.</li>
 * </ol>
 * Doet niets als {@code db2/} al bestaat (idempotent).
 */
public class Db2NaarPostgresChangelogs extends ScanningRecipe<Db2NaarPostgresChangelogs.Accumulator> {

    static final String STANDAARD_MASTER = "src/main/resources/db/changelog/db.changelog-master.yaml";
    static final String DB2_MASTER = "db.changelog-db2.yaml";
    static final String PG_MASTER = "db.changelog-postgres.yaml";

    @Option(displayName = "Master-changelog",
            description = "Pad van de YAML-master-changelog vanaf de root van het project.",
            example = STANDAARD_MASTER, required = false)
    private final String masterChangelog;

    @Option(displayName = "dbms voor bestaande changesets",
            description = "Waarde van `dbms` voor de bestaande changesets. Gebruik `db2,h2` zolang tests nog op H2 draaien.",
            example = "db2", required = false)
    private final String oudeDbms;

    @Option(displayName = "Postgres-schema",
            description = "Schema voor de preconditions en setval-statements. Leeg betekent: het search_path van de connectie.",
            example = "klant", required = false)
    private final String postgresSchema;

    transient MigratieBevindingen bevindingen = new MigratieBevindingen(this);

    public Db2NaarPostgresChangelogs() {
        this(null, null, null);
    }

    @JsonCreator
    public Db2NaarPostgresChangelogs(@JsonProperty("masterChangelog") String masterChangelog,
                                     @JsonProperty("oudeDbms") String oudeDbms,
                                     @JsonProperty("postgresSchema") String postgresSchema) {
        this.masterChangelog = masterChangelog;
        this.oudeDbms = oudeDbms;
        this.postgresSchema = postgresSchema;
    }

    public String getMasterChangelog() {
        return masterChangelog;
    }

    public String getOudeDbms() {
        return oudeDbms;
    }

    public String getPostgresSchema() {
        return postgresSchema;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Db2NaarPostgresChangelogs r
                && java.util.Objects.equals(masterChangelog, r.masterChangelog)
                && java.util.Objects.equals(oudeDbms, r.oudeDbms)
                && java.util.Objects.equals(postgresSchema, r.postgresSchema);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(masterChangelog, oudeDbms, postgresSchema);
    }

    @Override
    public String getDisplayName() {
        return "Liquibase-changelogs splitsen in db2/ en postgres/";
    }

    @Override
    public String getDescription() {
        return "Verplaatst de bestaande changelogs naar db2/ met dbms en logicalFilePath, neemt de "
                + "Postgres-changelogs op en zet de basisdata klaar voor een schone installatie.";
    }

    public static class Accumulator {
        boolean alGemigreerd;
        String masterTekst;
        boolean postgresMasterAanwezig;
        final Set<Path> postgresBestanden = new TreeSet<>();
        final PostgresSchema schema = new PostgresSchema();
    }

    // ------------------------------------------------------------------ paden

    private Path master() {
        return Paths.get(masterChangelog == null ? STANDAARD_MASTER : masterChangelog);
    }

    private Path root() {
        return master().getParent();
    }

    private Path db2Map() {
        return root().resolve("db2");
    }

    private Path pgMap() {
        return root().resolve("postgres");
    }

    private String dbms() {
        return oudeDbms == null || oudeDbms.isBlank() ? "db2" : oudeDbms;
    }

    private String schemaPrefix() {
        return postgresSchema == null || postgresSchema.isBlank() ? "" : postgresSchema.toLowerCase(Locale.ROOT) + ".";
    }

    // ------------------------------------------------------------------ scannen

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile sf)) {
                    return tree;
                }
                Path p = sf.getSourcePath();
                if (p.startsWith(db2Map())) {
                    acc.alGemigreerd = true;
                } else if (p.equals(master())) {
                    acc.masterTekst = sf.printAll();
                } else if (p.startsWith(pgMap())) {
                    if (p.getFileName().toString().equals(PG_MASTER)) {
                        acc.postgresMasterAanwezig = true;
                    } else if (sf instanceof Yaml.Documents && p.getParent().equals(pgMap())) {
                        acc.postgresBestanden.add(p);
                    }
                    if (sf instanceof Yaml.Documents docs) {
                        PostgresSchema.verzamel(docs, acc.schema);
                    }
                }
                return tree;
            }
        };
    }

    // ------------------------------------------------------------------ nieuwe bestanden

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.alGemigreerd) {
            return Collections.emptyList();
        }
        if (acc.masterTekst == null) {
            bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(master().toString(), 0, "Handmatig",
                    "Master-changelog niet gevonden; niets gemigreerd. Zet de optie masterChangelog."));
            return Collections.emptyList();
        }
        List<SourceFile> nieuw = new ArrayList<>();
        List<String> meldingen = new ArrayList<>();
        String db2Master = bewerkDb2Yaml(acc.masterTekst, ChangelogTekst.classpathPad(master().toString()), meldingen);
        nieuw.add(yaml(db2Map().resolve(DB2_MASTER), db2Master, ctx));
        meld(ctx, db2Map().resolve(DB2_MASTER), meldingen);

        if (!acc.postgresBestanden.isEmpty() && !acc.postgresMasterAanwezig) {
            List<String> includes = new ArrayList<>();
            acc.postgresBestanden.forEach(p -> includes.add(p.getFileName().toString()));
            nieuw.add(yaml(pgMap().resolve(PG_MASTER), ChangelogTekst.master(includes), ctx));
        }
        if (acc.postgresBestanden.isEmpty()) {
            bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(pgMap().toString(), 0, "Handmatig",
                    "Geen Postgres-changelogs gevonden. Kopieer eerst de gereviewde map naar " + pgMap() + "."));
        }
        return nieuw;
    }

    // ------------------------------------------------------------------ bestaande bestanden

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (acc.alGemigreerd || acc.masterTekst == null || !(tree instanceof SourceFile sf)) {
                    return tree;
                }
                Path p = sf.getSourcePath();
                if (p.equals(master())) {
                    List<String> includes = new ArrayList<>();
                    includes.add("db2/" + DB2_MASTER);
                    if (!acc.postgresBestanden.isEmpty() || acc.postgresMasterAanwezig) {
                        includes.add("postgres/" + PG_MASTER);
                    }
                    String nieuw = ChangelogTekst.master(includes);
                    if (!acc.masterTekst.endsWith("\n")) {
                        nieuw = nieuw.stripTrailing(); // afsluitende newline van het origineel overnemen
                    }
                    return herparseYaml(sf, nieuw, ctx);
                }
                if (p.startsWith(db2Map())) {
                    return tree; // net gegenereerd
                }
                if (p.startsWith(pgMap())) {
                    return bewerkPostgres(sf, acc, ctx);
                }
                if (p.startsWith(root())) {
                    return verplaatsNaarDb2(sf, ctx);
                }
                return tree;
            }
        };
    }

    private SourceFile verplaatsNaarDb2(SourceFile sf, ExecutionContext ctx) {
        Path oud = sf.getSourcePath();
        Path nieuw = db2Map().resolve(root().relativize(oud));
        String logischPad = ChangelogTekst.classpathPad(oud.toString());
        List<String> meldingen = new ArrayList<>();
        SourceFile resultaat = sf;

        if (sf instanceof Yaml.Documents) {
            String tekst = sf.printAll();
            if (ChangelogTekst.isYamlChangelog(tekst)) {
                resultaat = herparseYaml(sf, bewerkDb2Yaml(tekst, logischPad, meldingen), ctx);
            }
        } else if (sf instanceof PlainText pt && ChangelogTekst.isSqlChangelog(pt.getText())) {
            resultaat = pt.withText(ChangelogTekst.voegSqlAttributenToe(pt.getText(), dbms(), logischPad, meldingen));
        } else if (oud.toString().endsWith(".xml") && sf.printAll().contains("<databaseChangeLog")) {
            meldingen.add("XML-changelog verplaatst maar NIET aangepast: dbms en logicalFilePath handmatig toevoegen");
        } else if (oud.toString().endsWith(".json")) {
            meldingen.add("Bestand verplaatst; is dit een JSON-changelog, voeg dan handmatig dbms en logicalFilePath toe");
        }
        meld(ctx, nieuw, meldingen);
        return resultaat.withSourcePath(nieuw);
    }

    private String bewerkDb2Yaml(String tekst, String logischPad, List<String> meldingen) {
        String cpRoot = ChangelogTekst.classpathPad(root().toString()) + "/";
        String t = ChangelogTekst.herschrijfVerwijzingen(tekst, cpRoot);
        return ChangelogTekst.voegAttributenToe(t,
                ChangelogTekst.attributen("dbms", dbms(), "logicalFilePath", logischPad), meldingen);
    }

    private SourceFile bewerkPostgres(SourceFile sf, Accumulator acc, ExecutionContext ctx) {
        if (!(sf instanceof Yaml.Documents docs) || sf.getSourcePath().getFileName().toString().equals(PG_MASTER)) {
            return sf;
        }
        String naam = sf.getSourcePath().getFileName().toString().toLowerCase(Locale.ROOT);
        String origineel = sf.printAll();
        String tekst;
        if (naam.contains("basisdata")) {
            Yaml.Documents omgezet = (Yaml.Documents) new BasisdataWaarden(acc.schema).visitNonNull(docs, ctx);
            tekst = ChangelogTekst.basisdataAttributen(omgezet.printAll(), schemaPrefix());
            if (!origineel.endsWith("\n")) {
                tekst = tekst.stripTrailing(); // afsluitende newline van het origineel overnemen
            }
            for (String t : ChangelogTekst.tabellen(tekst)) {
                if (!acc.schema.kent(t)) {
                    bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(sf.getSourcePath().toString(), 0, "Handmatig",
                            "Basisdata voor tabel " + t + " die niet in de Postgres-baseline staat"));
                }
            }
        } else {
            List<String> meldingen = new ArrayList<>();
            tekst = ChangelogTekst.voegAttributenToe(origineel, ChangelogTekst.attributen("dbms", "postgresql"), meldingen);
            meld(ctx, sf.getSourcePath(), meldingen);
        }
        return tekst.equals(origineel) ? sf : herparseYaml(sf, tekst, ctx);
    }

    /**
     * Basisdata uit {@code liquibase generate-changelog --diff-types=data}: tabel- en kolomnamen
     * lowercase, boolean-kolommen (volgens de baseline) van 0/1 of J/N naar valueBoolean, en
     * gegenereerde ids als {@code 1727...-5} hernoemen naar {@code basisdata-<tabel>}.
     */
    static class BasisdataWaarden extends YamlIsoVisitor<ExecutionContext> {
        private static final Map<String, String> BOOLEANS = Map.of(
                "1", "true", "0", "false", "J", "true", "N", "false", "Y", "true", "T", "true", "F", "false",
                "TRUE", "true", "FALSE", "false");
        private final PostgresSchema schema;

        BasisdataWaarden(PostgresSchema schema) {
            this.schema = schema;
        }

        @Override
        public Yaml.Mapping visitMapping(Yaml.Mapping mapping, ExecutionContext ctx) {
            Yaml.Mapping m = super.visitMapping(mapping, ctx);
            Object ouder = getCursor().getParentTreeCursor().getValue();
            if (ouder instanceof Yaml.Mapping.Entry e) {
                String sleutel = e.getKey().getValue();
                if ("insert".equals(sleutel)) {
                    return bewerkInsert(m);
                }
                if ("changeSet".equals(sleutel)) {
                    return hernoemId(m);
                }
            }
            return m;
        }

        private Yaml.Mapping bewerkInsert(Yaml.Mapping insert) {
            String tabel = PostgresSchema.lower(PostgresSchema.scalar(insert, "tableName"));
            if (tabel == null) {
                return insert;
            }
            Map<String, String> kolommen = schema.kolommen(tabel);
            Yaml.Mapping m = zetScalar(insert, "tableName", tabel);
            return m.withEntries(ListUtils.map(m.getEntries(), e -> {
                if (!"columns".equals(e.getKey().getValue()) || !(e.getValue() instanceof Yaml.Sequence seq)) {
                    return e;
                }
                return e.withValue(seq.withEntries(ListUtils.map(seq.getEntries(), se -> {
                    if (!(se.getBlock() instanceof Yaml.Mapping omhulsel)) {
                        return se;
                    }
                    return se.withBlock(omhulsel.withEntries(ListUtils.map(omhulsel.getEntries(), ce ->
                            "column".equals(ce.getKey().getValue()) && ce.getValue() instanceof Yaml.Mapping kolom
                                    ? ce.withValue(bewerkKolom(kolom, kolommen))
                                    : ce)));
                })));
            }));
        }

        private Yaml.Mapping bewerkKolom(Yaml.Mapping kolom, Map<String, String> kolommen) {
            String naam = PostgresSchema.lower(PostgresSchema.scalar(kolom, "name"));
            if (naam == null) {
                return kolom;
            }
            Yaml.Mapping k = zetScalar(kolom, "name", naam);
            if (!"boolean".equals(kolommen.get(naam))) {
                return k;
            }
            return k.withEntries(ListUtils.map(k.getEntries(), e -> {
                String sleutel = e.getKey().getValue();
                if (!(sleutel.equals("valueNumeric") || sleutel.equals("value"))
                        || !(e.getValue() instanceof Yaml.Scalar waarde)
                        || !(e.getKey() instanceof Yaml.Scalar sleutelScalar)) {
                    return e;
                }
                String b = BOOLEANS.get(waarde.getValue().trim().toUpperCase(Locale.ROOT));
                return b == null ? e : e.withKey(sleutelScalar.withValue("valueBoolean")).withValue(waarde.withValue(b));
            }));
        }

        private Yaml.Mapping hernoemId(Yaml.Mapping changeSet) {
            String id = PostgresSchema.scalar(changeSet, "id");
            if (id == null || !id.matches("\\d+-\\d+")) {
                return changeSet;
            }
            Set<String> tabellen = new TreeSet<>();
            new YamlIsoVisitor<Set<String>>() {
                @Override
                public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry e, Set<String> s) {
                    if ("tableName".equals(e.getKey().getValue()) && e.getValue() instanceof Yaml.Scalar sc) {
                        s.add(sc.getValue().toLowerCase(Locale.ROOT));
                    }
                    return super.visitMappingEntry(e, s);
                }
            }.visit(changeSet, tabellen);
            return tabellen.size() == 1 ? zetScalar(changeSet, "id", "basisdata-" + tabellen.iterator().next()) : changeSet;
        }

        private static Yaml.Mapping zetScalar(Yaml.Mapping m, String sleutel, String waarde) {
            return m.withEntries(ListUtils.map(m.getEntries(), e ->
                    sleutel.equals(e.getKey().getValue()) && e.getValue() instanceof Yaml.Scalar s && !waarde.equals(s.getValue())
                            ? e.withValue(s.withValue(waarde))
                            : e));
        }
    }

    // ------------------------------------------------------------------ hulpjes

    private void meld(ExecutionContext ctx, Path pad, List<String> meldingen) {
        for (String m : meldingen) {
            bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(pad.toString(), 0, "Handmatig", m));
        }
    }

    private static Yaml.Documents yaml(Path pad, String tekst, ExecutionContext ctx) {
        Yaml.Documents d = parseYaml(tekst, ctx);
        return d.withSourcePath(pad);
    }

    private static SourceFile herparseYaml(SourceFile origineel, String tekst, ExecutionContext ctx) {
        Yaml.Documents d = parseYaml(tekst, ctx);
        d = d.withId(origineel.getId());
        d = d.withSourcePath(origineel.getSourcePath());
        d = d.withMarkers(origineel.getMarkers());
        d = d.withFileAttributes(origineel.getFileAttributes());
        return d;
    }

    private static Yaml.Documents parseYaml(String tekst, ExecutionContext ctx) {
        SourceFile sf = YamlParser.builder().build().parse(ctx, tekst).findFirst()
                .orElseThrow(() -> new IllegalStateException("YAML leeg na bewerking"));
        if (!(sf instanceof Yaml.Documents d)) {
            throw new IllegalStateException("YAML niet meer te parsen na bewerking:\n" + tekst);
        }
        return d;
    }
}
