package nl.example.klant.domein;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Scenario: entity zonder DB2-specifieke mapping. Blijft ongewijzigd. */
@Entity
@Table(name = "KLANTTYPE")
public class KlantType {

    @Id
    private Long id;

    private String code;

    public String getCode() {
        return code;
    }
}
