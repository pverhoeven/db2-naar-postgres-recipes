package nl.example.klant.domein;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import nl.example.klant.converter.BooleanConverter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.CharJdbcType;

import java.util.UUID;

@Entity
@Table(name = "BETAALMETHODE")
public class Betaalmethode {

    // CHAR(36) -> uuid: @JdbcType(CharJdbcType.class) weg
    @Id
    @JdbcType(CharJdbcType.class)
    private UUID id;

    private String code;

    // SMALLINT -> boolean, laatste gebruik van BooleanConverter in dit bestand: import ook weg
    @Convert(converter = BooleanConverter.class)
    private Boolean actief;

    // wordt uuid, maar het Java-type is String: melding "overweeg UUID"
    @Column(name = "REFERENTIE", length = 36)
    private String referentie;

    // staat niet in de Postgres-baseline: melding "kolom niet gevonden"
    @Column(name = "SORTERING")
    private Integer sortering;

    public UUID getId() {
        return id;
    }

    public Boolean getActief() {
        return actief;
    }
}
