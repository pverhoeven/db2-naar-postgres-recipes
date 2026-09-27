package nl.example.migratie;

import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Kolomtypes uit de (gereviewde) Postgres-changelogs.
 * Dit is de bron van waarheid voor de basisdata-omzetting en de entity-recipe.
 */
public class PostgresSchema {

    private final Map<String, Map<String, String>> tabellen = new HashMap<>();

    public boolean isLeeg() {
        return tabellen.isEmpty();
    }

    public boolean kent(String tabel) {
        return tabellen.containsKey(tabel);
    }

    /** kolom -> type (lowercase, zonder lengte), leeg als de tabel onbekend is. */
    public Map<String, String> kolommen(String tabel) {
        return tabellen.getOrDefault(tabel, Collections.emptyMap());
    }

    public java.util.Set<String> kolommenVanType(String type) {
        java.util.Set<String> s = new java.util.TreeSet<>();
        tabellen.values().forEach(k -> k.forEach((naam, t) -> {
            if (type.equals(t)) {
                s.add(naam);
            }
        }));
        return s;
    }

    void kolom(String tabel, String kolom, String type) {
        tabellen.computeIfAbsent(tabel, t -> new HashMap<>()).put(kolom, type);
    }

    /** Leest createTable, addColumn en modifyDataType uit een YAML-changelog. */
    public static void verzamel(Yaml.Documents documenten, PostgresSchema schema) {
        new YamlIsoVisitor<PostgresSchema>() {
            @Override
            public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, PostgresSchema s) {
                String sleutel = entry.getKey().getValue();
                if (entry.getValue() instanceof Yaml.Mapping m) {
                    switch (sleutel) {
                        case "createTable", "addColumn" -> leesKolommen(m, s);
                        case "modifyDataType" -> {
                            String t = lower(scalar(m, "tableName"));
                            String k = lower(scalar(m, "columnName"));
                            if (t != null && k != null) {
                                s.kolom(t, k, normaliseer(scalar(m, "newDataType")));
                            }
                        }
                        default -> {
                        }
                    }
                }
                return super.visitMappingEntry(entry, s);
            }
        }.visit(documenten, schema);
    }

    private static void leesKolommen(Yaml.Mapping m, PostgresSchema s) {
        String tabel = lower(scalar(m, "tableName"));
        if (tabel == null || !(waarde(m, "columns") instanceof Yaml.Sequence kolommen)) {
            return;
        }
        for (Yaml.Sequence.Entry se : kolommen.getEntries()) {
            if (se.getBlock() instanceof Yaml.Mapping omhulsel) {
                Map<String, String> kolom = velden(waarde(omhulsel, "column"));
                String naam = lower(kolom.get("name"));
                if (naam == null) {
                    continue;
                }
                s.kolom(tabel, naam, normaliseer(kolom.get("type")));
            }
        }
    }

    /**
     * Scalar-velden van een mapping. Sinds rewrite-yaml 8.8x wordt een flow-mapping zonder geneste
     * {@code {..}} (bv. {@code {name: id, type: UUID}}) als één Scalar geparset; die lezen we zelf.
     */
    static Map<String, String> velden(Yaml.Block blok) {
        Map<String, String> r = new HashMap<>();
        if (blok instanceof Yaml.Mapping m) {
            for (Yaml.Mapping.Entry e : m.getEntries()) {
                if (e.getValue() instanceof Yaml.Scalar sc) {
                    r.put(e.getKey().getValue(), sc.getValue());
                }
            }
        } else if (blok instanceof Yaml.Scalar sc && sc.getValue().trim().startsWith("{")) {
            String t = sc.getValue().trim();
            t = t.substring(1, t.endsWith("}") ? t.length() - 1 : t.length());
            int diepte = 0;
            int start = 0;
            for (int i = 0; i <= t.length(); i++) {
                char c = i < t.length() ? t.charAt(i) : ',';
                if (c == '{' || c == '[' || c == '(') {
                    diepte++;
                } else if (c == '}' || c == ']' || c == ')') {
                    diepte--;
                } else if (c == ',' && diepte == 0) {
                    String paar = t.substring(start, i);
                    int dp = paar.indexOf(':');
                    if (dp > 0) {
                        String waarde = paar.substring(dp + 1).trim();
                        if (!waarde.startsWith("{") && !waarde.startsWith("[")) {
                            r.put(paar.substring(0, dp).trim(), waarde.replaceAll("^['\"]|['\"]$", ""));
                        }
                    }
                    start = i + 1;
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ YAML-hulpjes

    public static String scalar(Yaml.Mapping m, String sleutel) {
        return waarde(m, sleutel) instanceof Yaml.Scalar s ? s.getValue() : null;
    }

    public static Yaml.Block waarde(Yaml.Mapping m, String sleutel) {
        for (Yaml.Mapping.Entry e : m.getEntries()) {
            if (sleutel.equals(e.getKey().getValue())) {
                return e.getValue();
            }
        }
        return null;
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    static String normaliseer(String type) {
        if (type == null) {
            return "";
        }
        String t = type.trim().toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)", "").trim();
        return switch (t) {
            case "bool", "java.sql.types.boolean" -> "boolean";
            case "int2" -> "smallint";
            case "int4", "int" -> "integer";
            case "int8" -> "bigint";
            default -> t;
        };
    }
}
