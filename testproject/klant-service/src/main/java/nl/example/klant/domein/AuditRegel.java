package nl.example.klant.domein;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import nl.example.klant.converter.BooleanConverter;

/** Scenario: tabel ontbreekt in de Postgres-baseline. Entity blijft ongewijzigd, met een melding. */
@Entity
@Table(name = "AUDIT_REGEL")
public class AuditRegel {

    @Id
    private Long id;

    @Convert(converter = BooleanConverter.class)
    private Boolean verwerkt;
}
