package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import rlvm.load.BlockClaims;

/**
 * {@code rlvm-admit} keeps a private copy of the claims-blob layout (it must not depend on the
 * loader): this test pins the copy to the writer's constants and exercises the shape validation.
 */
class ClaimsLayoutTest {

    @Test
    void offsetsEqualTheLoadersLayout() {
        assertEquals(BlockClaims.VERSION, Claims.VERSION);
        assertEquals(BlockClaims.HEADER, Claims.HEADER_WORDS);
        assertEquals(BlockClaims.H_VERSION, Claims.H_VERSION);
        assertEquals(BlockClaims.H_NUM_TYPES, Claims.H_TYPES);
        assertEquals(BlockClaims.H_NUM_CTORS, Claims.H_CTORS);
        assertEquals(BlockClaims.H_NUM_RECS, Claims.H_RECS);
        assertEquals(BlockClaims.H_RECS_OFFSET, Claims.H_REC_OFFSET);
        assertEquals(BlockClaims.TYPE_WORDS, Claims.TYPE_WORDS);
        assertEquals(BlockClaims.T_CID, Claims.T_CID);
        assertEquals(BlockClaims.T_NUM_PARAMS, Claims.T_NUM_PARAMS);
        assertEquals(BlockClaims.T_NUM_INDICES, Claims.T_NUM_INDICES);
        assertEquals(BlockClaims.T_NUM_NESTED, Claims.T_NUM_NESTED);
        assertEquals(BlockClaims.T_FLAGS, Claims.T_FLAGS);
        assertEquals(BlockClaims.T_ALL, Claims.T_ALL);
        assertEquals(BlockClaims.T_CTORS, Claims.T_CTORS);
        assertEquals(BlockClaims.CTOR_WORDS, Claims.CTOR_WORDS);
        assertEquals(BlockClaims.C_CID, Claims.C_CID);
        assertEquals(BlockClaims.C_INDUCT, Claims.C_INDUCT);
        assertEquals(BlockClaims.C_CIDX, Claims.C_CIDX);
        assertEquals(BlockClaims.C_NUM_PARAMS, Claims.C_NUM_PARAMS);
        assertEquals(BlockClaims.C_NUM_FIELDS, Claims.C_NUM_FIELDS);
        assertEquals(BlockClaims.C_FLAGS, Claims.C_FLAGS);
        assertEquals(BlockClaims.REC_WORDS, Claims.REC_WORDS);
        assertEquals(BlockClaims.R_CID, Claims.R_CID);
        assertEquals(BlockClaims.R_ALL, Claims.R_ALL);
        assertEquals(BlockClaims.R_NUM_PARAMS, Claims.R_NUM_PARAMS);
        assertEquals(BlockClaims.R_NUM_INDICES, Claims.R_NUM_INDICES);
        assertEquals(BlockClaims.R_NUM_MOTIVES, Claims.R_NUM_MOTIVES);
        assertEquals(BlockClaims.R_NUM_MINORS, Claims.R_NUM_MINORS);
        assertEquals(BlockClaims.R_FLAGS, Claims.R_FLAGS);
        assertEquals(BlockClaims.R_NUM_RULES, Claims.R_NUM_RULES);
        assertEquals(BlockClaims.RULE_WORDS, Claims.RULE_WORDS);
        assertEquals(BlockClaims.RULE_CTOR, Claims.RULE_CTOR);
        assertEquals(BlockClaims.RULE_NFIELDS, Claims.RULE_NFIELDS);
        assertEquals(BlockClaims.RULE_RHS, Claims.RULE_RHS);
        assertEquals(BlockClaims.F_REC, Claims.F_IS_REC);
        assertEquals(BlockClaims.F_REFLEXIVE, Claims.F_IS_REFLEXIVE);
        assertEquals(BlockClaims.F_UNSAFE, Claims.F_UNSAFE);
        assertEquals(BlockClaims.F_K, Claims.F_K);
    }

    @Test
    void longsAreLowHigh() {
        int[] blob = {0, 0, 0x89ABCDEF, 0x01234567};
        assertEquals(0x0123456789ABCDEFL, Claims.getLong(blob, 2));
        assertEquals(BlockClaims.getLong(blob, 2), Claims.getLong(blob, 2));
        int[] negativeLow = {-1, 0};
        assertEquals(0xFFFF_FFFFL, Claims.getLong(negativeLow, 0));
    }

    @Test
    void validateRejectsEveryTruncation() {
        assertNotNull(Claims.validate(null));
        assertNotNull(Claims.validate(new int[3]));
        int[] wrongVersion = new int[8];
        wrongVersion[Claims.H_VERSION] = 2;
        assertNotNull(Claims.validate(wrongVersion));

        // one type, one constructor, one recursor with two rules
        int n = Claims.HEADER_WORDS + Claims.TYPE_WORDS + Claims.CTOR_WORDS + Claims.REC_WORDS + 2 * Claims.RULE_WORDS;
        int[] blob = new int[n];
        blob[Claims.H_VERSION] = Claims.VERSION;
        blob[Claims.H_TYPES] = 1;
        blob[Claims.H_CTORS] = 1;
        blob[Claims.H_RECS] = 1;
        int rec = Claims.HEADER_WORDS + Claims.TYPE_WORDS + Claims.CTOR_WORDS;
        blob[Claims.H_REC_OFFSET] = rec;
        blob[rec + Claims.R_NUM_RULES] = 2;
        assertNull(Claims.validate(blob));
        assertEquals(Claims.HEADER_WORDS, Claims.typeEntry(0));
        assertEquals(Claims.HEADER_WORDS + Claims.TYPE_WORDS, Claims.ctorEntry(blob, 0));
        assertEquals(rec, Claims.firstRec(blob));
        assertEquals(n, Claims.nextRec(blob, rec));

        for (int cut = 1; cut < n; cut++) {
            assertNotNull(Claims.validate(java.util.Arrays.copyOf(blob, n - cut)), "cut " + cut);
        }
        int[] negative = blob.clone();
        negative[Claims.H_CTORS] = -1;
        assertNotNull(Claims.validate(negative));
        int[] badOffset = blob.clone();
        badOffset[Claims.H_REC_OFFSET] = 4;
        assertNotNull(Claims.validate(badOffset));
        int[] badRules = blob.clone();
        badRules[rec + Claims.R_NUM_RULES] = -3;
        assertNotNull(Claims.validate(badRules));
        int[] manyRules = blob.clone();
        manyRules[rec + Claims.R_NUM_RULES] = Integer.MAX_VALUE;
        assertNotNull(Claims.validate(manyRules));
    }
}
