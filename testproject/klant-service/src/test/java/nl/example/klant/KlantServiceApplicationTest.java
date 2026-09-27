package nl.example.klant;

import jakarta.persistence.EntityManager;
import nl.example.klant.domein.Betaalmethode;
import nl.example.klant.domein.Klant;
import nl.example.klant.repository.KlantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
class KlantServiceApplicationTest {

    @Autowired
    KlantRepository klanten;

    @Autowired
    EntityManager em;

    @Test
    void liquibaseEnMappingsWerken() {
        assertThat(klanten.actiefJpql()).extracting(Klant::getNaam).containsExactly("Voorbeeldklant");
    }

    @Test
    void uuidEnBooleanUitDeBasisdata() {
        assertThat(em.createQuery("select b from Betaalmethode b", Betaalmethode.class).getResultList())
          .extracting(Betaalmethode::getId, Betaalmethode::getActief)
          .containsExactlyInAnyOrder(
            tuple(UUID.fromString("3f2b8c1e-8d4a-4c7e-9b1a-2e6f5d4c3b2a"), true),
            tuple(UUID.fromString("7a1c9e2d-4b3f-4e8a-a6d5-1c2b3a4f5e6d"), false));
    }
}
