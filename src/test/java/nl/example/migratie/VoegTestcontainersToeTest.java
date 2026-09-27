package nl.example.migratie;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class VoegTestcontainersToeTest implements RewriteTest {

    private static final String APP = """
      package nl.example.klant;

      import org.springframework.boot.autoconfigure.SpringBootApplication;

      @SpringBootApplication
      public class KlantServiceApplication {
          public static void main(String[] args) {
          }
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new VoegTestcontainersToe("postgres:17"))
          .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void genereertConfiguratieEnLocalApplicatieEnImporteertInTests() {
        rewriteRun(
          java(APP, s -> s.path("src/main/java/nl/example/klant/KlantServiceApplication.java")),
          java(
            """
              package nl.example.klant.repository;

              import org.springframework.boot.test.context.SpringBootTest;

              @SpringBootTest
              class KlantRepositoryTest {
              }
              """,
            """
              package nl.example.klant.repository;

              import nl.example.klant.TestcontainersConfiguration;
              import org.springframework.boot.test.context.SpringBootTest;
              import org.springframework.context.annotation.Import;

              @Import(TestcontainersConfiguration.class)
              @SpringBootTest
              class KlantRepositoryTest {
              }
              """,
            s -> s.path("src/test/java/nl/example/klant/repository/KlantRepositoryTest.java")),
          java(
            null,
            """
              package nl.example.klant;

              import org.springframework.boot.test.context.TestConfiguration;
              import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
              import org.springframework.context.annotation.Bean;
              import org.testcontainers.postgresql.PostgreSQLContainer;
              import org.testcontainers.utility.DockerImageName;

              /** Postgres voor tests en voor {@link LocalKlantServiceApplication}. */
              @TestConfiguration(proxyBeanMethods = false)
              public class TestcontainersConfiguration {

                  @Bean
                  @ServiceConnection
                  PostgreSQLContainer postgresContainer() {
                      return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
                  }
              }
              """,
            s -> s.path("src/test/java/nl/example/klant/TestcontainersConfiguration.java")),
          java(
            null,
            """
              package nl.example.klant;

              import org.springframework.boot.SpringApplication;

              /** Start KlantServiceApplication lokaal met Postgres in een testcontainer. */
              public class LocalKlantServiceApplication {

                  public static void main(String[] args) {
                      SpringApplication.from(KlantServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
                  }
              }
              """,
            s -> s.path("src/test/java/nl/example/klant/LocalKlantServiceApplication.java"))
        );
    }

    @Test
    void doetNietsAlsConfiguratieAlBestaat() {
        rewriteRun(
          java(APP, s -> s.path("src/main/java/nl/example/klant/KlantServiceApplication.java")),
          java(
            """
              package nl.example.klant;

              public class TestcontainersConfiguration {
              }
              """, s -> s.path("src/test/java/nl/example/klant/TestcontainersConfiguration.java")),
          java(
            """
              package nl.example.klant;

              import org.springframework.boot.test.context.SpringBootTest;

              @SpringBootTest
              class KlantTest {
              }
              """, s -> s.path("src/test/java/nl/example/klant/KlantTest.java"))
        );
    }
}
