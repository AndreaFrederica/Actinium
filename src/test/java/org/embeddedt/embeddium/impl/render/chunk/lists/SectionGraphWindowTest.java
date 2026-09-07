package org.embeddedt.embeddium.impl.render.chunk.lists;

import org.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The terrain and shadow passes share one {@link SectionLattice} and submit their searches back to
 * back from {@code RenderSectionManager.updateForShadowPass}. While a search is in flight on the
 * search thread, the lattice arrays must stay structurally stable: a window rebase rewrites
 * regionOfCell/visitState in place, and a concurrent search then reads the uninstalled-cell marker
 * (-1) out of regionOfCell, crashing in RegionCullCache.classify. These tests pin the two halves of
 * the fix: window preparation fails fast while a search is in flight, and submitting the shadow
 * search no longer moves the window at all.
 */
class SectionGraphWindowTest {
    // 12-chunk render distance, in blocks.
    private static final float SEARCH_DISTANCE = 12 * 16.0f;

    private static Viewport viewportAt(double x, double y, double z) {
        // A frustum that reports everything visible; the window logic under test never consults it.
        return new Viewport((minX, minY, minZ, maxX, maxY, maxZ) -> true, new Vector3d(x, y, z));
    }

    @Test
    void ensureWindowCoversRejectedWhileSearchInFlight() {
        var graph = new SectionGraph(0, 16, AsyncOcclusionMode.EVERYTHING, true);

        try {
            var terrain = new RenderListManager(graph, false, AsyncOcclusionMode.EVERYTHING, null);
            terrain.startGraphUpdate(viewportAt(8, 64, 8), 1, 1, SEARCH_DISTANCE, false, Integer.MAX_VALUE);

            // The submitted search has not been joined: any window preparation now would race it.
            assertThrows(IllegalStateException.class,
                    () -> graph.ensureWindowCovers(new Vector3i(16000, 4, 16000), SEARCH_DISTANCE));

            terrain.finishPreviousGraphUpdate();
        } finally {
            graph.destroy();
        }
    }

    @Test
    void shadowSubmitDoesNotMoveWindowWhileTerrainSearchInFlight() {
        var graph = new SectionGraph(0, 16, AsyncOcclusionMode.EVERYTHING, true);

        try {
            var terrain = new RenderListManager(graph, false, AsyncOcclusionMode.EVERYTHING, null);
            var shadow = new RenderListManager(graph, true, AsyncOcclusionMode.EVERYTHING, null);

            // Terrain search rooted at chunk (0, 4, 0): the window dimension is 2 * (12 + 4) + 3 = 35,
            // so the window base lands at 0 - 35 / 2 = -17 on X and Z.
            terrain.startGraphUpdate(viewportAt(8, 64, 8), 1, 1, SEARCH_DISTANCE, false, Integer.MAX_VALUE);

            // A stale-vs-current viewport pair (e.g. a teleport between frames) lands the shadow
            // camera thousands of chunks away. Submitting the shadow search must not rebase the
            // shared window out from under the in-flight terrain search.
            shadow.startShadowGraphUpdate(viewportAt(16008, 64, 16008), 1, 1, SEARCH_DISTANCE, null, Integer.MAX_VALUE);

            terrain.finishPreviousGraphUpdate();
            shadow.finishPreviousGraphUpdate();

            var snapshot = graph.getLattice().findVisible((latticeIndex, regionId, sectionIndex, meta, visible) -> {
            }, viewportAt(8, 64, 8), SEARCH_DISTANCE, 1, false, false, 2);

            assertEquals(-17, snapshot.baseX());
            assertEquals(-17, snapshot.baseZ());
        } finally {
            graph.destroy();
        }
    }
}
