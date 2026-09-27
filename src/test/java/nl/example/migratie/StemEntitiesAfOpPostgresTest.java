package nl.example.migratie;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.yaml.Assertions.yaml;

class StemEntitiesAfOpPostgresTest implements RewriteTest {

    private static final String BASELINE = """
      databaseChangeLog:
        - changeSet:
            id: pg-baseline-1
            author: db2-migratie
            changes:
              - createTable:
                  tableName: bestelling
                  columns:
                    - column: {name: id, type: UUID}
                    - column: {name: betaald, type: BOOLEAN}
                    - column: {name: status_code, type: SMALLINT}
      """;

    private static final String CONVERTER = """
      package nl.example;

      import jakarta.persistence.AttributeConverter;
      import jakarta.persistence.Converter;

      @Converter
      public class BooleanConverter implements AttributeConverter<Boolean, Short> {
          public Short convertToDatabaseColumn(Boolean b) { return b == null ? null : (short) (b ? 1 : 0); }
          public Boolean convertToEntityAttribute(Short s) { return s == null ? null : s == 1; }
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new StemEntitiesAfOpPostgres())
          .parser(JavaParser.fromJavaVersion().classpath("jakarta.persistence-api", "hibernate-core"))
          .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void alleenOpKolommenDieVeranderdZijn() {
        rewriteRun(
          yaml(BASELINE, s -> s.path("src/main/resources/db/changelog/postgres/000-baseline.yaml")),
          java(CONVERTER),
          java(
            """
              package nl.example;

              import jakarta.persistence.Column;
              import jakarta.persistence.Convert;
              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;
              import org.hibernate.annotations.JdbcTypeCode;
              import org.hibernate.type.SqlTypes;

              import java.util.UUID;

              @Entity
              public class Bestelling {

                  @Id
                  @JdbcTypeCode(SqlTypes.CHAR)
                  private UUID id;

                  @Convert(converter = BooleanConverter.class)
                  @Column(name = "BETAALD")
                  private Boolean betaald;

                  @Convert(converter = BooleanConverter.class)
                  @Column(name = "STATUS_CODE")
                  private Boolean afgerond;
              }
              """,
            """
              package nl.example;

              import jakarta.persistence.Column;
              import jakarta.persistence.Convert;
              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              import java.util.UUID;

              @Entity
              public class Bestelling {

                  @Id
                  private UUID id;

                  @Column(name = "BETAALD")
                  private Boolean betaald;

                  @Convert(converter = BooleanConverter.class)
                  @Column(name = "STATUS_CODE")
                  private Boolean afgerond;
              }
              """
          )
        );
    }

    @Test
    void baselineMetGenesteFlowMappingsEnCommentaar() {
        rewriteRun(
          yaml(
            """
              databaseChangeLog:
                - changeSet:
                    id: pg-baseline-1
                    author: db2-migratie
                    changes:
                      - createTable:
                          tableName: klant
                          columns:
                            - column: {name: id, type: BIGINT, autoIncrement: true, constraints: {primaryKey: true}}
                            # besluit in de review
                            - column: {name: actief, type: BOOLEAN}
              """, s -> s.path("src/main/resources/db/changelog/postgres/000-baseline.yaml")),
          java(CONVERTER),
          java(
            """
              package nl.example;

              import jakarta.persistence.Convert;
              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              public class Klant {

                  @Id
                  private Long id;

                  @Convert(converter = BooleanConverter.class)
                  private Boolean actief;
              }
              """,
            """
              package nl.example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              public class Klant {

                  @Id
                  private Long id;

                  private Boolean actief;
              }
              """
          )
        );
    }

    @Test
    void importsWegAlsNietsMeerGebruiktWordt() {
        rewriteRun(
          yaml(BASELINE, s -> s.path("src/main/resources/db/changelog/postgres/000-baseline.yaml")),
          java(CONVERTER),
          java(
            """
              package nl.example;

              import jakarta.persistence.Convert;
              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              import java.util.UUID;

              @Entity
              public class Bestelling {

                  @Id
                  private UUID id;

                  @Convert(converter = BooleanConverter.class)
                  private Boolean betaald;
              }
              """,
            """
              package nl.example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              import java.util.UUID;

              @Entity
              public class Bestelling {

                  @Id
                  private UUID id;

                  private Boolean betaald;
              }
              """
          )
        );
    }

    @Test
    void importsWegOokAlsCommentaarDeNaamNoemt() {
        rewriteRun(
          yaml(BASELINE, s -> s.path("src/main/resources/db/changelog/postgres/000-baseline.yaml")),
          java(CONVERTER.replace("package nl.example;", "package nl.example.converter;")),
          java(
            """
              package nl.example;

              import jakarta.persistence.Convert;
              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;
              import nl.example.converter.BooleanConverter;
              import org.hibernate.annotations.JdbcTypeCode;
              import org.hibernate.type.SqlTypes;

              import java.util.UUID;

              @Entity
              public class Bestelling {

                  // was @JdbcTypeCode(SqlTypes.CHAR)
                  @Id
                  @JdbcTypeCode(SqlTypes.CHAR)
                  private UUID id;

                  // BooleanConverter: 0/1
                  @Convert(converter = BooleanConverter.class)
                  private Boolean betaald;
              }
              """,
            """
              package nl.example;

              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              import java.util.UUID;

              @Entity
              public class Bestelling {

                  // was @JdbcTypeCode(SqlTypes.CHAR)
                  @Id
                  private UUID id;

                  // BooleanConverter: 0/1
                  private Boolean betaald;
              }
              """
          )
        );
    }

    @Test
    void zonderPostgresChangelogGebeurtNiets() {
        rewriteRun(
          java(CONVERTER),
          java(
            """
              package nl.example;

              import jakarta.persistence.Convert;
              import jakarta.persistence.Entity;
              import jakarta.persistence.Id;

              @Entity
              public class Bestelling {
                  @Id
                  private Long id;

                  @Convert(converter = BooleanConverter.class)
                  private Boolean betaald;
              }
              """
          )
        );
    }
}
