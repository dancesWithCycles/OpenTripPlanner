package org.opentripplanner.routing.edgetype;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.vertextype.SimpleVertex;
import org.opentripplanner.util.NonLocalizedString;

class PathwayEdgeTest {

    @Test
    void testZeroLengthPathway() {

        // GTFS pathways don't need to define traversal_time, distance or steps
        // in such a case we compute the traversal time from the distance of the vertices rather
        // than rendering them unpassable

        var graph = new Graph();
        var from = new SimpleVertex(graph, "A", 10, 10);
        var to = new SimpleVertex(graph, "B", 10.001, 10.001);

        var edge = new PathwayEdge(
                from,
                to,
                null,
                new NonLocalizedString("pathway"),
                0,
                0,
                0,
                0,
                true
        );

        assertTrue(edge.getEffectiveWalkDistance() > 0);

        var req = new RoutingRequest();
        req.setRoutingContext(graph, from, to);
        var state = new State(req);

        var ret = edge.traverse(state);
        assertNotNull(ret);

        assertTrue(ret.getElapsedTimeSeconds() > 0);
        assertTrue(ret.getWeight() > 0);
    }

}