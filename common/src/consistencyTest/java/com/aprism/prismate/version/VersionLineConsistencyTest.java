package com.aprism.prismate.version;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aprism.loader.remap.McProfile;
import com.aprism.loader.remap.VersionLineEntry;
import com.aprism.loader.remap.VersionLineRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift alarm: the Prismate-owned JE version line must stay in lockstep with
 * the Aprism core {@code VersionLineRegistry} (Aprism v26.1-Alpha.7). The two
 * projects develop in parallel and Aprism keeps moving, so any divergence —
 * a new segment, a changed baseline, a moved line window — must fail this test
 * loudly instead of silently shipping a stale mirror (v26.1-Alpha.1).
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateVersionLine mirrors the Aprism VersionLineRegistry")
class VersionLineConsistencyTest {

    @Test
    @DisplayName("line window matches upstream")
    void lineWindowMatches() {
        assertThat(PrismateVersionLine.LINE_START).isEqualTo(VersionLineRegistry.LINE_START);
        assertThat(PrismateVersionLine.LINE_END).isEqualTo(VersionLineRegistry.LINE_END);
        assertThat(PrismateVersionLine.describeLine())
                .isEqualTo(VersionLineRegistry.describeLine());
    }

    @Test
    @DisplayName("every upstream segment is mirrored field-by-field")
    void segmentsMatchUpstream() {
        List<VersionLineEntry> upstream = VersionLineRegistry.supportedLine();
        List<PrismateVersionLine.Segment> mirror = PrismateVersionLine.supportedLine();
        assertThat(mirror).hasSameSizeAs(upstream);
        for (int i = 0; i < upstream.size(); i++) {
            VersionLineEntry entry = upstream.get(i);
            PrismateVersionLine.Segment segment = mirror.get(i);
            assertThat(segment.minorPrefix()).as("segment %d prefix", i)
                    .isEqualTo(entry.versionId());
            assertThat(segment.remapped()).as("segment %d remap profile", i)
                    .isEqualTo(entry.profile() == McProfile.REMAPPED);
            assertThat(segment.javaBaseline()).as("segment %d java baseline", i)
                    .isEqualTo(entry.javaBaseline());
            assertThat(segment.mappingsSource()).as("segment %d mappings source", i)
                    .isEqualTo(entry.mappingsSource());
        }
    }

    @Test
    @DisplayName("resolve() agrees with upstream across the line and below it")
    void resolutionAgrees() {
        String[] probes = {
                "1.20", "1.20.1", "1.20.4", "1.21", "1.21.10", "26.1.2", "26.2",
                "26.5",
                "1.19.4", "1.16.5", "garbage", "", null
        };
        for (String probe : probes) {
            boolean upstreamResolves = VersionLineRegistry.resolve(probe).isPresent();
            boolean mirrorResolves = PrismateVersionLine.resolve(probe).isPresent();
            assertThat(mirrorResolves).as("resolve('%s')", probe)
                    .isEqualTo(upstreamResolves);
            if (upstreamResolves && mirrorResolves) {
                VersionLineEntry entry = VersionLineRegistry.resolve(probe).orElseThrow();
                PrismateVersionLine.Segment segment =
                        PrismateVersionLine.resolve(probe).orElseThrow();
                assertThat(segment.minorPrefix())
                        .as("resolve('%s').prefix", probe)
                        .isEqualTo(upstreamPrefix(entry));
                assertThat(segment.remapped())
                        .as("resolve('%s').remapped", probe)
                        .isEqualTo(entry.profile() == McProfile.REMAPPED);
                assertThat(segment.javaBaseline())
                        .as("resolve('%s').javaBaseline", probe)
                        .isEqualTo(entry.javaBaseline());
            }
        }
    }

    @Test
    @DisplayName("isWithinSupportedLine() agrees with upstream")
    void withinLineAgrees() {
        String[] probes = {
                "1.20", "1.20.4", "1.21.10", "26.1.2", "26.2", "26.5", "1.19.4"
        };
        for (String probe : probes) {
            assertThat(PrismateVersionLine.isWithinSupportedLine(probe))
                    .as("isWithinSupportedLine('%s')", probe)
                    .isEqualTo(VersionLineRegistry.isWithinSupportedLine(probe));
        }
    }

    /**
     * Maps a resolved upstream entry back to its segment prefix. The
     * (profile, javaBaseline, mappingsSource) triple uniquely identifies one
     * segment (1.20: REMAPPED/17/intermediary, 1.21: REMAPPED/21/intermediary,
     * 26: NO_REMAP/25/none), so a match on the triple yields the prefix.
     */
    private static String upstreamPrefix(VersionLineEntry entry) {
        for (VersionLineEntry segment : VersionLineRegistry.supportedLine()) {
            if (segment.profile() == entry.profile()
                    && segment.javaBaseline() == entry.javaBaseline()
                    && segment.mappingsSource().equals(entry.mappingsSource())) {
                return segment.versionId();
            }
        }
        return entry.versionId();
    }
}
