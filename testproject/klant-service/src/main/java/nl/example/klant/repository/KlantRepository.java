package nl.example.klant.repository;

import nl.example.klant.domein.Klant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface KlantRepository extends JpaRepository<Klant, Long> {

    // boolean-kolom vergeleken met 1, en WITH UR: beide gemeld
    @Query(value = "SELECT * FROM KLANT k WHERE k.ACTIEF = 1 AND k.VIP <> 0 WITH UR", nativeQuery = true)
    List<Klant> actieveKlanten();

    // CURRENT TIMESTAMP en SYSIBM: DB2-SQL gemeld
    @Query(value = "SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1", nativeQuery = true)
    java.sql.Timestamp nu();

    // DB2 for i-catalogus
    @Query(value = "SELECT COUNT(*) FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = 'KLANTDB'", nativeQuery = true)
    long aantalTabellen();

    // JPQL met een boolean-parameter: geen melding, werkt op beide databases
    @Query("select k from Klant k where k.actief = true")
    List<Klant> actiefJpql();
}
