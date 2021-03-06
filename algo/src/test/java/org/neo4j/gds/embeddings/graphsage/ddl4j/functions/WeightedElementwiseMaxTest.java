/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gds.embeddings.graphsage.ddl4j.functions;

import org.junit.jupiter.api.Test;
import org.neo4j.gds.embeddings.graphsage.NeighborhoodFunction;
import org.neo4j.gds.embeddings.graphsage.ddl4j.FiniteDifferenceTest;
import org.neo4j.gds.embeddings.graphsage.ddl4j.GraphSageBaseTest;
import org.neo4j.gds.embeddings.graphsage.ddl4j.helper.ElementSum;
import org.neo4j.gds.embeddings.graphsage.ddl4j.tensor.Matrix;
import org.neo4j.gds.embeddings.graphsage.subgraph.SubGraph;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.RelationshipCursor;
import org.neo4j.graphalgo.extension.GdlExtension;
import org.neo4j.graphalgo.extension.GdlGraph;
import org.neo4j.graphalgo.extension.IdFunction;
import org.neo4j.graphalgo.extension.Inject;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@GdlExtension
class WeightedElementwiseMaxTest extends GraphSageBaseTest implements FiniteDifferenceTest {

    @GdlGraph(orientation = Orientation.UNDIRECTED)
    private static final String DB_CYPHER =
        "CREATE" +
        "  (u1:User { id: 0 })" +
        ", (u1:User { id: 1 })" +
        ", (d1:Dish { id: 2 })" +
        ", (d2:Dish { id: 3 })" +
        ", (d3:Dish { id: 4 })" +
        ", (d4:Dish { id: 5 })" +
        ", (u1)-[:ORDERED {times: 5}]->(d1)" +
        ", (u1)-[:ORDERED {times: 2}]->(d2)" +
        ", (u1)-[:ORDERED {times: 1}]->(d3)" +
        ", (u2)-[:ORDERED {times: 2}]->(d3)" +
        ", (u2)-[:ORDERED {times: 3}]->(d4)";

    @Inject
    Graph graph;

    @Inject
    private IdFunction idFunction;

    @Test
    void shouldApplyWeightsToEmbeddings() {
        long[] ids = new long[]{
            idFunction.of("d1"),
            idFunction.of("d2"),
            idFunction.of("d3"),
            idFunction.of("d4"),
        };
        NeighborhoodFunction neighborhoodFunction = (graph, nodeId) -> graph
            .streamRelationships(nodeId, 0.0D)
            .mapToLong(RelationshipCursor::targetId)
            .boxed()
            .collect(Collectors.toList());
        SubGraph subGraph = SubGraph.buildSubGraphs(ids, List.of(neighborhoodFunction), graph).get(0);

        double[] userEmbeddingsData = new double[] {
            1, 1, 1, // u1
            1, 1, 1, // u2
            1, 1, 1, // d1
            3, 3, 3, // d2
            1, 1, 1, // d3
            1, 1, 1 // d4
        };

        MatrixConstant userEmbeddings = new MatrixConstant(userEmbeddingsData, 6, 3);
        ctx.forward(userEmbeddings);
        WeightedElementwiseMax weightedEmbeddings = new WeightedElementwiseMax(
            userEmbeddings,
            graph::relationshipProperty,
            subGraph
        );

        Matrix matrix = weightedEmbeddings.apply(ctx);

        double[] expected = new double[] {
            5.0, 5.0, 5.0, // d1
            2.0, 2.0, 2.0, // d2
            2.0, 2.0, 2.0, // d3
            3.0, 3.0, 3.0, // d4
        };

        assertThat(matrix.data()).isEqualTo(expected);
    }

    @Test
    void testGradient() {
        long[] ids = new long[]{
            idFunction.of("d1"),
            idFunction.of("d2"),
            idFunction.of("d3"),
            idFunction.of("d4"),
        };
        NeighborhoodFunction neighborhoodFunction = (graph, nodeId) -> graph
            .streamRelationships(nodeId, 0.0D)
            .mapToLong(RelationshipCursor::targetId)
            .boxed()
            .collect(Collectors.toList());
        SubGraph subGraph = SubGraph.buildSubGraphs(ids, List.of(neighborhoodFunction), graph).get(0);

        double[] userEmbeddingsData = new double[] {
            1, 1, 1, // u1
            2, 2, 2, // u2
            3, 3, 3, // d1
            4, 4, 4, // d2
            5, 5, 5, // d3
            6, 6, 6 // d4
        };

        Weights<Matrix> weights = new Weights<>(new Matrix(userEmbeddingsData, 6, 3));

        finiteDifferenceShouldApproximateGradient(
            weights,
            new ElementSum(List.of(new WeightedElementwiseMax(
                weights,
                graph::relationshipProperty,
                subGraph
            )))
        );
    }
}
