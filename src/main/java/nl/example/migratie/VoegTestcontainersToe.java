package nl.example.migratie;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Zet de tests op Testcontainers met Postgres, zoals Spring Boot dat zelf genereert:
 * <ul>
 *     <li>{@code TestcontainersConfiguration} in {@code src/test/java}, naast de applicatieklasse, met een
 *     {@code @ServiceConnection}-container;</li>
 *     <li>{@code Local<App>}: start de applicatie lokaal met die container (in plaats van een DB via podman);</li>
 *     <li>{@code @Import(TestcontainersConfiguration.class)} op {@code @SpringBootTest}- en {@code @DataJpaTest}-klassen.</li>
 * </ul>
 * Bestaat {@code TestcontainersConfiguration} al, dan doet de recipe niets.
 */
public class VoegTestcontainersToe extends ScanningRecipe<VoegTestcontainersToe.Scan> {

    private static final String CONFIG = "TestcontainersConfiguration";
    private static final Set<String> TESTANNOTATIES = Set.of("SpringBootTest", "DataJpaTest");

    @Option(displayName = "Postgres-image",
            description = "Image voor de testcontainer; gelijk houden met de Postgres-versie in productie.",
            example = "postgres:17", required = false)
    private final String postgresImage;

    transient MigratieBevindingen bevindingen = new MigratieBevindingen(this);

    public VoegTestcontainersToe() {
        this(null);
    }

    @JsonCreator
    public VoegTestcontainersToe(@JsonProperty("postgresImage") String postgresImage) {
        this.postgresImage = postgresImage;
    }

    public String getPostgresImage() {
        return postgresImage;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VoegTestcontainersToe r && Objects.equals(postgresImage, r.postgresImage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postgresImage);
    }

    @Override
    public String getDisplayName() {
        return "Testcontainers-configuratie en Local-applicatie toevoegen";
    }

    @Override
    public String getDescription() {
        return "Genereert TestcontainersConfiguration en Local<App> in src/test/java en importeert de configuratie "
                + "in @SpringBootTest- en @DataJpaTest-klassen.";
    }

    public static class Scan {
        String pakket;
        String app;
        Path testRoot;
        boolean configBestaat;
        int applicaties;
    }

    @Override
    public Scan getInitialValue(ExecutionContext ctx) {
        return new Scan();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Scan scan) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof J.CompilationUnit cu)) {
                    return tree;
                }
                String pad = cu.getSourcePath().toString().replace('\\', '/');
                if (pad.contains("src/test/java/") && pad.endsWith("/" + CONFIG + ".java")) {
                    scan.configBestaat = true;
                }
                int i = pad.indexOf("src/main/java/");
                if (i < 0) {
                    return tree;
                }
                for (J.ClassDeclaration k : cu.getClasses()) {
                    if (k.getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals("SpringBootApplication"))) {
                        scan.applicaties++;
                        scan.app = k.getSimpleName();
                        scan.pakket = cu.getPackageDeclaration() == null ? ""
                                : cu.getPackageDeclaration().getPackageName();
                        scan.testRoot = Paths.get(pad.substring(0, i) + "src/test/java");
                    }
                }
                return tree;
            }
        };
    }

    private boolean actief(Scan scan) {
        return scan.applicaties == 1 && !scan.configBestaat;
    }

    @Override
    public Collection<? extends SourceFile> generate(Scan scan, ExecutionContext ctx) {
        if (!actief(scan)) {
            return List.of();
        }
        String pkg = scan.pakket.isEmpty() ? "" : "package " + scan.pakket + ";\n\n";
        String image = postgresImage == null ? "postgres:17" : postgresImage;
        String config = pkg + """
                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
                import org.springframework.context.annotation.Bean;
                import org.testcontainers.postgresql.PostgreSQLContainer;
                import org.testcontainers.utility.DockerImageName;

                /** Postgres voor tests en voor {@link Local%1$s}. */
                @TestConfiguration(proxyBeanMethods = false)
                public class TestcontainersConfiguration {

                    @Bean
                    @ServiceConnection
                    PostgreSQLContainer postgresContainer() {
                        return new PostgreSQLContainer(DockerImageName.parse("%2$s"));
                    }
                }
                """.formatted(scan.app, image);
        String local = pkg + """
                import org.springframework.boot.SpringApplication;

                /** Start %1$s lokaal met Postgres in een testcontainer. */
                public class Local%1$s {

                    public static void main(String[] args) {
                        SpringApplication.from(%1$s::main).with(TestcontainersConfiguration.class).run(args);
                    }
                }
                """.formatted(scan.app);
        Path map = scan.pakket.isEmpty() ? scan.testRoot : scan.testRoot.resolve(scan.pakket.replace('.', '/'));
        List<SourceFile> nieuw = new ArrayList<>();
        JavaParser.fromJavaVersion().build().parse(ctx, config, local).forEach(sf -> {
            String naam = ((J.CompilationUnit) sf).getClasses().get(0).getSimpleName();
            nieuw.add(sf.withSourcePath(map.resolve(naam + ".java")));
        });
        return nieuw;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Scan scan) {
        if (!actief(scan)) {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof J.CompilationUnit cu && scan.applicaties != 1 && !scan.configBestaat
                            && cu.getSourcePath().toString().contains("src/main/java")
                            && cu.getClasses().stream().anyMatch(k -> k.getLeadingAnnotations().stream()
                            .anyMatch(a -> a.getSimpleName().equals("SpringBootApplication")))) {
                        bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(cu.getSourcePath().toString(), 0,
                                "Handmatig", scan.applicaties + " @SpringBootApplication-klassen; Testcontainers-configuratie niet gegenereerd"));
                    }
                    return tree;
                }
            };
        }
        String configFqn = scan.pakket.isEmpty() ? CONFIG : scan.pakket + "." + CONFIG;
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration k = super.visitClassDeclaration(classDecl, ctx);
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (cu == null || !cu.getSourcePath().toString().replace('\\', '/').contains("src/test/java/")
                        || k.getLeadingAnnotations().stream().noneMatch(a -> TESTANNOTATIES.contains(a.getSimpleName()))
                        || k.getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals("Import"))) {
                    return k;
                }
                k = JavaTemplate.builder("@Import(" + CONFIG + ".class)")
                        .build()
                        .apply(updateCursor(k), k.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                doAfterVisit(new AddImport<>("org.springframework.context.annotation.Import", null, false));
                String eigenPakket = cu.getPackageDeclaration() == null ? ""
                        : cu.getPackageDeclaration().getPackageName();
                if (!eigenPakket.equals(scan.pakket)) {
                    doAfterVisit(new AddImport<>(configFqn, null, false));
                }
                bevindingen.insertRow(ctx, new MigratieBevindingen.Rij(cu.getSourcePath().toString(), 0,
                        "Aangepast", k.getSimpleName() + ": @Import(" + CONFIG + ".class) toegevoegd"));
                return k;
            }
        };
    }
}
