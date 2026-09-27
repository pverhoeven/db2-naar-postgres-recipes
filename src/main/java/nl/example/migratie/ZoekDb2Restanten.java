package nl.example.migratie;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.binary.Binary;
import org.openrewrite.quark.Quark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Meldt DB2-restanten die na de automatische stappen nog over zijn. Past niets aan; alles gaat
 * naar de datatabel {@link MigratieBevindingen}. Hoort als laatste in de lijst te staan.
 */
public class ZoekDb2Restanten extends Recipe {

    private static final Map<String, Pattern> CONTROLES = new LinkedHashMap<>();

    static {
        CONTROLES.put("DB2-driver, URL of dialect", Pattern.compile(
                "jdbc:db2|jdbc:as400|com\\.ibm\\.db2|com\\.ibm\\.as400|jt400|DB2i?Dialect|DB2Driver|AS400JDBCDriver"));
        CONTROLES.put("DB2-specifieke SQL", Pattern.compile(
                "\\bWITH\\s+UR\\b|\\bCURRENT\\s+(TIMESTAMP|DATE|TIME)\\b|\\bNEXT\\s+VALUE\\s+FOR\\b|\\bPREVIOUS\\s+VALUE\\s+FOR\\b"
                        + "|\\bSYSIBM\\.|\\bSYSCAT\\.|\\bQSYS2\\.|\\bVALUE\\s*\\(|\\bDAYS\\s*\\(", Pattern.CASE_INSENSITIVE));
        CONTROLES.put("CHAR-mapping voor uuid", Pattern.compile("SqlTypes\\.(N?VAR)?CHAR|uuid-char|preferred_uuid_jdbc_type"));
        CONTROLES.put("Schema in hoofdletters (Postgres gebruikt lowercase)",
                Pattern.compile("(default[-_]schema|currentSchema|libraries)[^a-z\\n]*[:=][ ]*[A-Z_]{2,}"));
        CONTROLES.put("H2 (tests en lokaal)", Pattern.compile("h2database|jdbc:h2"));
    }

    transient MigratieBevindingen bevindingen = new MigratieBevindingen(this);

    @Override
    public boolean equals(Object o) {
        return o instanceof ZoekDb2Restanten;
    }

    @Override
    public int hashCode() {
        return ZoekDb2Restanten.class.hashCode();
    }

    @Override
    public String getDisplayName() {
        return "DB2-restanten zoeken";
    }

    @Override
    public String getDescription() {
        return "Meldt DB2-drivers, DB2-specifieke SQL, CHAR-mappings voor uuid, schemanamen in hoofdletters en H2 in een datatabel.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile sf) || sf instanceof Quark || sf instanceof Binary) {
                    return tree;
                }
                String pad = sf.getSourcePath().toString().replace('\\', '/');
                if (pad.contains("/db/changelog/db2/") || pad.startsWith("target/") || pad.contains("/target/")) {
                    return tree;
                }
                String[] regels = sf.printAll().split("\n");
                for (int i = 0; i < regels.length; i++) {
                    for (Map.Entry<String, Pattern> c : CONTROLES.entrySet()) {
                        if (c.getValue().matcher(regels[i]).find()) {
                            bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(pad, i + 1, "Handmatig",
                                    c.getKey() + ": " + regels[i].trim()));
                        }
                    }
                }
                return tree;
            }
        };
    }
}
