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
package org.neo4j.gds.embeddings.fastrp;

import org.jetbrains.annotations.TestOnly;
import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.core.utils.mem.MemoryEstimation;
import org.neo4j.graphalgo.core.utils.mem.MemoryEstimations;
import org.neo4j.graphalgo.core.utils.mem.MemoryUsage;
import org.neo4j.graphalgo.core.utils.paged.HugeObjectArray;
import org.neo4j.graphalgo.utils.CloseableThreadLocal;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.neo4j.gds.embeddings.EmbeddingUtils.getCheckedDoubleNodeProperty;
import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

public class FastRP extends Algorithm<FastRP, FastRP> {

    private static final int SPARSITY = 3;
    private final Graph graph;
    private final int concurrency;
    private final float normalizationStrength;
    private final List<String> nodePropertyNames;
    private final float[][] propertyVectors;
    private final HugeObjectArray<float[]> embeddings;
    private final HugeObjectArray<float[]> embeddingA;
    private final HugeObjectArray<float[]> embeddingB;
    private final EmbeddingCombiner embeddingCombiner;

    private final int embeddingDimension;
    private final int baseEmbeddingDimension;
    private final List<Number> iterationWeights;

    public static MemoryEstimation memoryEstimation(FastRPBaseConfig config) {
        return MemoryEstimations
            .builder(FastRP.class)
            .fixed(
                "propertyVectors",
                MemoryUsage.sizeOfFloatArray(config.nodePropertyNames().size() * config.propertyDimension())
            )
            .add("embeddings", HugeObjectArray.memoryEstimation(MemoryUsage.sizeOfFloatArray(config.embeddingDimension())))
            .add("embeddingA", HugeObjectArray.memoryEstimation(MemoryUsage.sizeOfFloatArray(config.embeddingDimension())))
            .add("embeddingB", HugeObjectArray.memoryEstimation(MemoryUsage.sizeOfFloatArray(config.embeddingDimension())))
            .build();
    }

    public FastRP(
        Graph graph,
        FastRPBaseConfig config,
        ProgressLogger progressLogger,
        AllocationTracker tracker
    ) {
        this.graph = graph;
        this.progressLogger = progressLogger;
        this.nodePropertyNames = config.nodePropertyNames();

        this.propertyVectors = new float[nodePropertyNames.size()][config.propertyDimension()];
        this.embeddings = HugeObjectArray.newArray(float[].class, graph.nodeCount(), tracker);
        this.embeddingA = HugeObjectArray.newArray(float[].class, graph.nodeCount(), tracker);
        this.embeddingB = HugeObjectArray.newArray(float[].class, graph.nodeCount(), tracker);

        this.embeddingDimension = config.embeddingDimension();
        this.baseEmbeddingDimension = config.embeddingDimension() - config.propertyDimension();
        this.iterationWeights = config.iterationWeights();
        this.normalizationStrength = config.normalizationStrength();
        this.concurrency = config.concurrency();
        this.embeddingCombiner = graph.hasRelationshipProperty()
            ? this::addArrayValuesWeighted
            : (lhs, rhs, ignoreWeight) -> addArrayValues(lhs, rhs);
        this.embeddings.setAll((i) -> new float[embeddingDimension]);
    }

    @Override
    public FastRP compute() {
        progressLogger.logMessage(":: Start");
        initPropertyVectors();
        initRandomVectors();
        propagateEmbeddings();
        progressLogger.logMessage(":: Finished");
        return me();
    }

    public HugeObjectArray<float[]> embeddings() {
        return this.embeddings;
    }

    @TestOnly
    HugeObjectArray<float[]> currentEmbedding(int iteration) {
        return iteration % 2 == 0
            ? this.embeddingA
            : this.embeddingB;
    }

    @Override
    public FastRP me() {
        return this;
    }

    @Override
    public void release() {
        this.embeddingA.release();
        this.embeddingB.release();
    }

    private void initPropertyVectors() {
        int propertyDimension = embeddingDimension - baseEmbeddingDimension;
        float entryValue = (float) Math.sqrt(SPARSITY) / (float) Math.sqrt(propertyDimension);
        double probability = 1.0f / (2.0f * SPARSITY);
        ThreadLocal<Random> random = ThreadLocal.withInitial(HighQualityRandom::new);
        for (int i = 0; i < nodePropertyNames.size(); i++) {
            this.propertyVectors[i] = new float[propertyDimension];
            for (int d = 0; d < propertyDimension; d++) {
                this.propertyVectors[i][d] = computeRandomEntry(random.get(), probability, entryValue);
            }
        }
    }

    void initRandomVectors() {
        double probability = 1.0f / (2.0f * SPARSITY);
        float sqrtSparsity = (float) Math.sqrt(SPARSITY);
        float sqrtEmbeddingDimension = (float) Math.sqrt(baseEmbeddingDimension);
        ThreadLocal<Random> random = ThreadLocal.withInitial(HighQualityRandom::new);

        progressLogger.logMessage("Initialising Random Vectors :: Start");
        ParallelUtil.parallelForEachNode(graph, concurrency, nodeId -> {
            int degree = graph.degree(nodeId);
            float scaling = degree == 0
                ? 1.0f
                : (float) Math.pow(degree, normalizationStrength);

            float entryValue = scaling * sqrtSparsity / sqrtEmbeddingDimension;
            float[] randomVector = computeRandomVector(nodeId, random.get(), probability, entryValue);
            embeddingB.set(nodeId, randomVector);
            embeddingA.set(nodeId, new float[this.embeddingDimension]);
            progressLogger.logProgress();
        });
        progressLogger.logMessage("Initialising Random Vectors :: Finished");
    }

    void propagateEmbeddings() {
        for (int i = 0; i < iterationWeights.size(); i++) {
            progressLogger.reset(graph.relationshipCount());
            progressLogger.logMessage(formatWithLocale("Iteration %s :: Start", i + 1));

            var localCurrent = i % 2 == 0 ? embeddingA : embeddingB;
            var localPrevious = i % 2 == 0 ? embeddingB : embeddingA;
            double iterationWeight = iterationWeights.get(i).doubleValue();

            try (var concurrentGraphCopy = CloseableThreadLocal.withInitial(graph::concurrentCopy)) {
                ParallelUtil.parallelForEachNode(graph, concurrency, nodeId -> {
                    float[] embedding = embeddings.get(nodeId);
                    float[] currentEmbedding = localCurrent.get(nodeId);
                    Arrays.fill(currentEmbedding, 0.0f);

                    // Collect and combine the neighbour embeddings
                    concurrentGraphCopy.get().forEachRelationship(nodeId, 1.0, (source, target, weight) -> {
                        embeddingCombiner.combine(currentEmbedding, localPrevious.get(target), weight);
                        return true;
                    });

                    // Normalize neighbour embeddings
                    var degree = graph.degree(nodeId);
                    int adjustedDegree = degree == 0 ? 1 : degree;
                    double degreeScale = 1.0f / adjustedDegree;
                    multiplyArrayValues(currentEmbedding, degreeScale);
                    l2Normalize(currentEmbedding);

                    // Update the result embedding
                    updateEmbeddings(iterationWeight, embedding, currentEmbedding);

                    progressLogger.logProgress(degree);
                });
            }

            progressLogger.logMessage(formatWithLocale("Iteration %s :: Finished", i + 1));
        }
    }

    private float[] computeRandomVector(long nodeId, Random random, double probability, float entryValue) {
        float[] randomVector = new float[embeddingDimension];
        for (int i = 0; i < embeddingDimension; i++) {
            randomVector[i] = computeRandomEntry(random, probability, entryValue);
        }
        for (int j = 0; j < nodePropertyNames.size(); j++) {
            String feature = nodePropertyNames.get(j);
            double featureValue = getCheckedDoubleNodeProperty(graph, feature, nodeId);
            if (featureValue != 0.0D) {
                for (int i = baseEmbeddingDimension; i < embeddingDimension; i++) {
                    randomVector[i] += featureValue * propertyVectors[j][i - baseEmbeddingDimension];
                }
            }
        }
        return randomVector;
    }

    private float computeRandomEntry(Random random, double probability, float entryValue) {
        double randomValue = random.nextDouble();

        if (randomValue < probability) {
            return entryValue;
        } else if (randomValue < probability * 2.0f) {
            return -entryValue;
        } else {
            return 0.0f;
        }
    }

    private void updateEmbeddings(double weight, float[] embedding, float[] newEmbedding) {
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] += weight * newEmbedding[i];
        }
    }

    private void addArrayValues(float[] lhs, float[] rhs) {
        for (int i = 0; i < lhs.length; i++) {
            lhs[i] += rhs[i];
        }
    }

    private void addArrayValuesWeighted(float[] lhs, float[] rhs, double weight) {
        for (int i = 0; i < lhs.length; i++) {
            lhs[i] = (float) Math.fma(rhs[i], weight, lhs[i]);
        }
    }

    private void multiplyArrayValues(float[] lhs, double scalar) {
        for (int i = 0; i < lhs.length; i++) {
            lhs[i] *= scalar;
        }
    }

    static void l2Normalize(float[] array) {
        double sum = 0.0f;
        for (double value : array) {
            sum += value * value;
        }
        double sqrtSum = sum == 0 ? 1 : Math.sqrt(sum);
        double scaling = 1 / sqrtSum;
        for (int i = 0; i < array.length; i++) {
            array[i] *= scaling;
        }
    }

    private static class HighQualityRandom extends Random {
        private long u;
        private long v = 4101842887655102017L;
        private long w = 1;

        public HighQualityRandom() {
            this(System.nanoTime() + (13 * Thread.currentThread().getId()));
        }

        public HighQualityRandom(long seed) {
            u = seed ^ v;
            nextLong();
            v = u;
            nextLong();
            w = v;
            nextLong();
        }

        @Override
        public long nextLong() {
            u = u * 2862933555777941757L + 7046029254386353087L;
            v ^= v >>> 17;
            v ^= v << 31;
            v ^= v >>> 8;
            w = 4294957665L * w + (w >>> 32);
            long x = u ^ (u << 21);
            x ^= x >>> 35;
            x ^= x << 4;
            return (x + v) ^ w;
        }

        protected int next(int bits) {
            return (int) (nextLong() >>> (64-bits));
        }
    }

    private interface EmbeddingCombiner {
        void combine(float[] into, float[] add, double weight);
    }
}
