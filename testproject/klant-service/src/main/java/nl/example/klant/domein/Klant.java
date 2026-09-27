package nl.example.klant.domein;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import nl.example.klant.converter.BooleanConverter;
import nl.example.klant.converter.JaNeeBooleanConverter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "KLANT")
public class Klant extends BasisEntiteit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "NAAM")
    private String naam;

    // SMALLINT 0/1 -> boolean: @Convert weg
    @Convert(converter = BooleanConverter.class)
    @Column(name = "ACTIEF")
    private Boolean actief;

    // CHAR(1) J/N -> boolean: @Convert en de import van JaNeeBooleanConverter weg
    @Column(name = "NIEUWSBRIEF")
    @Convert(converter = JaNeeBooleanConverter.class)
    private Boolean nieuwsbrief;

    // blijft SMALLINT in Postgres (besluit in de review): @Convert blijft staan
    @Convert(converter = BooleanConverter.class)
    @Column(name = "GEBLOKKEERD")
    private Boolean geblokkeerd;

    // wordt boolean, maar het Java-type is Short: melding "type aanpassen naar Boolean"
    @Column(name = "MAIL_OPTIN")
    private Short mailOptin;

    // boolean-veld op een SMALLINT-kolom zonder converter: melding
    @Column(name = "VIP")
    private boolean vip;

    // CHAR(36) -> uuid: @JdbcTypeCode en columnDefinition weg, name blijft
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "EXTERNE_ID", columnDefinition = "CHAR(36)")
    private UUID externeId;

    // relatie: wordt overgeslagen
    @ManyToOne
    @JoinColumn(name = "KLANTTYPE_ID")
    private KlantType klantType;

    // embedded: wordt overgeslagen (de @Embeddable zelf wordt gemeld)
    @Embedded
    private Adres adres;

    public Long getId() {
        return id;
    }

    public String getNaam() {
        return naam;
    }

    public Boolean getActief() {
        return actief;
    }
}
