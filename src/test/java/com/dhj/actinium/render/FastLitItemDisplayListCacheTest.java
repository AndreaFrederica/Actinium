package com.dhj.actinium.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the FFP context fingerprint used by the fast lit item display list cache
 * (issue #118): a cached list compiled under one fixed-function context must not be
 * replayed under a different one, so the fingerprint packing has to make every
 * program-variant selection input observable to the equality check.
 */
class FastLitItemDisplayListCacheTest {

    private static final long VERTEX_LIT = 0x1L;
    private static final long VERTEX_UNLIT = 0x0L;

    @Test
    void fingerprintLengthMatchesSlotLayout() {
        long[] fragment = {0xAL, 0xBL};
        long[] fingerprint = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, fragment, 2);

        assertEquals(5, fingerprint.length);
    }

    @Test
    void fingerprintCarriesVertexKeyInSlotZero() {
        long[] fingerprint = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[0], 0);

        assertTrue(fingerprint[0] == VERTEX_LIT);
    }

    @Test
    void fingerprintCarriesFragmentUnitsAndZeroFillsTheTail() {
        long[] fragment = {0xAL, 0xBL};
        long[] fingerprint = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, fragment, 2);

        assertTrue(fingerprint[1] == 0xAL);
        assertTrue(fingerprint[2] == 0xBL);
        assertTrue(fingerprint[3] == 0L);
        assertTrue(fingerprint[4] == 0L);
    }

    @Test
    void identicalContextsMatch() {
        long[] compiled = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xAL}, 1);
        long[] current = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xAL}, 1);

        assertTrue(FastLitItemDisplayListCache.contextFingerprintsMatch(compiled, current));
    }

    /**
     * Drawer TESR vs inventory GUI case: the vertex (lighting) side differs.
     */
    @Test
    void lightingChangeIsDetected() {
        long[] compiled = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xAL}, 1);
        long[] current = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_UNLIT, new long[]{0xAL}, 1);

        assertFalse(FastLitItemDisplayListCache.contextFingerprintsMatch(compiled, current));
    }

    /**
     * Enabled texture-unit count change (fragment side): unit 1 is the lightmap unit, and
     * zero-filling the tail must turn a shrinking/growing unit count into a mismatch.
     */
    @Test
    void fragmentUnitCountChangeIsDetected() {
        long[] compiled = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xAL, 0xBL}, 2);
        long[] current = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xAL}, 1);

        assertFalse(FastLitItemDisplayListCache.contextFingerprintsMatch(compiled, current));
    }

    @Test
    void fragmentUnitStateChangeIsDetected() {
        long[] compiled = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xAL}, 1);
        long[] current = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, new long[]{0xCL}, 1);

        assertFalse(FastLitItemDisplayListCache.contextFingerprintsMatch(compiled, current));
    }

    @Test
    void packingIsLosslessForIdenticalInputs() {
        long[] fragment = {0x11L, 0x22L, 0x33L};
        long[] a = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, fragment, 3);
        long[] b = FastLitItemDisplayListCache.packFfpContextFingerprint(VERTEX_LIT, fragment, 3);

        assertArrayEquals(a, b);
        assertTrue(FastLitItemDisplayListCache.contextFingerprintsMatch(a, b));
    }
}
