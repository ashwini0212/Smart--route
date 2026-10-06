package com.smartroute.routing;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadNetworkProviderTest {

    private final RoadNetworkProvider provider = new RoadNetworkProvider(
            new RoutingProperties(null, 20, 20, 42, 300, Duration.ofMinutes(10)), Clock.systemUTC());

    private Edge someEdge() {
        return provider.current().graph().outgoing(0).getFirst();
    }

    @Test
    void loadsTheSyntheticCityRestrictedToItsLargestComponent() {
        RoadNetwork network = provider.current();
        assertThat(network.synthetic()).isTrue();
        assertThat(network.source()).contains("synthetic 20x20");
        assertThat(network.graph().nodeCount()).isBetween(380, 400);
        assertThat(network.version()).isEqualTo(1);
    }

    @Test
    void trafficCreatesANewVersionAndLeavesTheOldSnapshotUntouched() {
        RoadNetwork before = provider.current();
        Edge edge = someEdge();
        RoadNetwork after = provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 3.0));

        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
        Edge slowed = after.graph().outgoing(edge.from()).stream().filter(e -> e.to() == edge.to()).findFirst().orElseThrow();
        assertThat(slowed.travelTimeSeconds()).isEqualTo(edge.travelTimeSeconds() * 3.0);
        assertThat(slowed.distanceMeters()).isEqualTo(edge.distanceMeters());
        // A request that took the old snapshot keeps a consistent view.
        assertThat(before.graph().outgoing(edge.from()).getFirst().travelTimeSeconds()).isEqualTo(edge.travelTimeSeconds());
        assertThat(provider.current()).isSameAs(after);
    }

    @Test
    void fingerprintDependsOnContentNotOnHistory() {
        Edge edge = someEdge();
        String base = provider.current().fingerprint();
        String first = provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 2.0)).fingerprint();
        provider.replaceTraffic(Map.of());
        assertThat(provider.current().fingerprint()).isEqualTo(base);
        String again = provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 2.0)).fingerprint();
        assertThat(again).isEqualTo(first);
    }

    @Test
    void multiplierOfOneIsTheSameAsNoTraffic() {
        Edge edge = someEdge();
        String base = provider.current().fingerprint();
        RoadNetwork network = provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 1.0));
        assertThat(network.traffic()).isEmpty();
        assertThat(network.fingerprint()).isEqualTo(base);
    }

    @Test
    void rejectsSpeedUpsAndUnknownSegments() {
        Edge edge = someEdge();
        assertThatThrownBy(() -> provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 0.5)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 11.0)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.replaceTraffic(Map.of(new RoadNetwork.EdgeKey(0, 0), 2.0)))
                .isInstanceOf(ApiException.class);
        assertThat(provider.current().version()).isEqualTo(1);
    }
}
