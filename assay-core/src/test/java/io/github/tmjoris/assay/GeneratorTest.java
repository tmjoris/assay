package io.github.tmjoris.assay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tmjoris.assay.gen.DataGen;
import io.github.tmjoris.assay.gen.QueryGen;
import io.github.tmjoris.assay.sql.Query;
import io.github.tmjoris.assay.sql.SqlType;
import io.github.tmjoris.assay.sql.Template;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GeneratorTest {

    @Test
    void sameSeedGivesSameQueries() {
        QueryGen a = new QueryGen(42);
        QueryGen b = new QueryGen(42);
        for (int i = 0; i < 500; i++) {
            assertEquals(a.next().sql(), b.next().sql());
        }
    }

    @Test
    void differentSeedsGiveDifferentQueries() {
        assertNotEquals(new QueryGen(1).next().sql(), new QueryGen(2).next().sql());
    }

    @Test
    void generatesAllTheShapesItPromises() {
        QueryGen gen = new QueryGen(7);
        boolean join = false, group = false, window = false, setOp = false, in = false, exists = false, hint = false;
        for (int i = 0; i < 3000; i++) {
            Query q = gen.next();
            String s = q.sql();
            join |= s.contains(" JOIN ");
            group |= s.contains(" GROUP BY ");
            window |= s.contains(" OVER (");
            setOp |= q instanceof Query.SetOp;
            in |= s.contains(" NOT IN (SELECT") || s.contains(" IN (SELECT");
            exists |= s.contains("EXISTS (");
            hint |= s.contains("/*+");
        }
        assertTrue(join && group && window && setOp && in && exists && hint);
    }

    @Test
    void neverGeneratesLeftoverPlaceholders() {
        QueryGen gen = new QueryGen(11);
        for (int i = 0; i < 2000; i++) {
            String s = gen.next().sql();
            assertFalse(s.matches(".*\\{\\d}.*"), s);
        }
    }

    @Test
    void placeholderInsideAStringLiteralIsLeftAlone() {
        Template t = new Template("concat({0}, {1})", SqlType.STRING, List.of(SqlType.STRING, SqlType.STRING), Template.Kind.SCALAR);
        assertEquals("concat('{1}', 'x')", t.render(List.of("'{1}'", "'x'")));
    }

    @Test
    void dataIsDeterministicAndLiteralsCoverEveryType() {
        assertEquals(new DataGen(5, 40).setup(), new DataGen(5, 40).setup());
        Random r = new Random(1);
        for (SqlType type : SqlType.values()) {
            assertFalse(DataGen.literal(type, r).isBlank());
        }
    }

    @Test
    void deleteStatementMatchesTheRowsItDropsFromTheModel() {
        DataGen g = new DataGen(5, 50);
        g.setup();
        assertEquals(40, g.rows("t3").size());
    }
}
