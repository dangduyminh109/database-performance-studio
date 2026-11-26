package studio.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.core.SqlCommandType;
import studio.performance.benchmark.Analysis;
import studio.performance.postgres.Postgres;
import studio.performance.shared.Input;
import studio.performance.shared.Vault;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StudioTest {
    @Test
    void vaultUsesRandomNoncesAndBindsCiphertextToItsResource() {
        Vault vault = new Vault(Base64.getEncoder().encodeToString(new byte[32]));
        UUID resource = UUID.randomUUID();
        String sealed = vault.encrypt(resource, "user password");
        assertEquals("user password", vault.decrypt(resource, sealed));
        assertNotEquals(sealed, vault.encrypt(resource, "user password"));
        assertThrows(IllegalStateException.class, () -> vault.decrypt(UUID.randomUUID(), sealed));
    }

    @Test
    void queryValidationAllowsParametersAndQuotedSemicolons() throws Exception {
        assertEquals(SqlCommandType.SELECT, Postgres.validate("select ?::text || ';';", true));
        assertEquals(SqlCommandType.UPDATE, Postgres.validate("update users set name = ?; ", false));
        assertThrows(IllegalArgumentException.class, () -> Postgres.validate("select 1; select 2", false));
        assertThrows(IllegalArgumentException.class, () -> Postgres.validate("delete from users", true));
        assertThrows(IllegalArgumentException.class, () -> Postgres.validate("set role postgres", false));
        assertEquals("\"example\"\"table\"", Input.identifier("example\"table"));
    }

    @Test
    void analyzerRequiresEvidenceBeforeFlaggingASequentialScan() throws Exception {
        Postgres pg = new Postgres(new ObjectMapper());
        Analysis analysis = new Analysis();
        assertTrue(analysis.recommend(pg, pg.json.readTree("""
            [{"Plan":{"Node Type":"Seq Scan","Actual Rows":1000,"Plan Rows":1000,"Rows Removed by Filter":2}}]
            """)).isEmpty());
        assertEquals(1, analysis.recommend(pg, pg.json.readTree("""
            [{"Plan":{"Node Type":"Seq Scan","Actual Rows":1,"Plan Rows":1,"Rows Removed by Filter":2000,
            "Filter":"(name = 'a'::text)","Relation Name":"users","Schema":"public"}}]
            """)).size());
    }
}
