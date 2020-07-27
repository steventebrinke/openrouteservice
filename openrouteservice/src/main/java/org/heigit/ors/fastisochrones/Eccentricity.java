package org.heigit.ors.fastisochrones;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.storage.index.LocationIndex;
import org.heigit.ors.fastisochrones.partitioning.storage.*;
import org.heigit.ors.routing.algorithms.DijkstraOneToManyAlgorithm;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.EdgeFilterSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;

import static org.heigit.ors.fastisochrones.partitioning.FastIsochroneParameters.getMaxCellNodesNumber;
import static org.heigit.ors.fastisochrones.partitioning.FastIsochroneParameters.getMaxThreadCount;

/**
 * Eccentricity implementation. Calculates the maximum value of all shortest paths within a cell given a starting bordernode.
 * Further calculates all distance pairs of bordernodes.
 * <p>
 *
 * @author Hendrik Leuschner
 */
public class Eccentricity extends AbstractEccentricity {
    //This value determines how many nodes of a cell need to be reached in order for the cell to count as fully reachable.
    //Some nodes might be part of a cell but unreachable (disconnected, behind infinite weight, ...)
    double acceptedFullyReachablePercentage = 0.995;
    int eccentricityDijkstraLimitFactor = 10;
    LocationIndex locationIndex;

    public Eccentricity(GraphHopperStorage graphHopperStorage, LocationIndex locationIndex, IsochroneNodeStorage isochroneNodeStorage, CellStorage cellStorage) {
        super(graphHopperStorage);
        this.locationIndex = locationIndex;
        this.isochroneNodeStorage = isochroneNodeStorage;
        this.cellStorage = cellStorage;
    }

    public void calcEccentricities(Weighting weighting, FlagEncoder flagEncoder) {
        if (eccentricityStorages == null) {
            eccentricityStorages = new ArrayList<>();
        }
        EccentricityStorage eccentricityStorage = getEccentricityStorage(weighting);
        Graph graph = ghStorage.getBaseGraph();
        if (!eccentricityStorage.loadExisting())
            eccentricityStorage.init();
        ExecutorService threadPool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(getMaxThreadCount(), Runtime.getRuntime().availableProcessors()));

        ExecutorCompletionService<String> completionService = new ExecutorCompletionService<>(threadPool);

        EdgeFilter defaultEdgeFilter = DefaultEdgeFilter.outEdges(flagEncoder);

        IntObjectHashMap<IntHashSet> relevantNodesSets = new IntObjectHashMap<>(isochroneNodeStorage.getCellIds().size());
        for (IntCursor cellId : isochroneNodeStorage.getCellIds()) {
            relevantNodesSets.put(cellId.value, getRelevantContourNodes(cellId.value, cellStorage, isochroneNodeStorage));
        }

        //Calculate the eccentricity via RangeDijkstra
        int borderNodeCount = 0;
        for (int borderNode = 0; borderNode < graph.getNodes(); borderNode++) {
            if (!isochroneNodeStorage.getBorderness(borderNode))
                continue;
            final int node = borderNode;
            borderNodeCount++;
            completionService.submit(() -> {
                //First run dijkstra only in cell and try to find _all_ nodes in the cell
                EdgeFilterSequence edgeFilterSequence = new EdgeFilterSequence();
                FixedCellEdgeFilter fixedCellEdgeFilter = new FixedCellEdgeFilter(isochroneNodeStorage, isochroneNodeStorage.getCellId(node), graph.getNodes());
                edgeFilterSequence.add(defaultEdgeFilter);
                edgeFilterSequence.add(fixedCellEdgeFilter);
                RangeDijkstra rangeDijkstra = new RangeDijkstra(graph, weighting);
                rangeDijkstra.setMaxVisitedNodes(getMaxCellNodesNumber() * eccentricityDijkstraLimitFactor);
                rangeDijkstra.setAcceptedFullyReachablePercentage(1.0);
                rangeDijkstra.setEdgeFilter(edgeFilterSequence);
                rangeDijkstra.setCellNodes(cellStorage.getNodesOfCell(isochroneNodeStorage.getCellId(node)));
                double eccentricity = rangeDijkstra.calcMaxWeight(node, relevantNodesSets.get(isochroneNodeStorage.getCellId(node)));
                int cellNodeCount = cellStorage.getNodesOfCell(isochroneNodeStorage.getCellId(node)).size();
                //Rerun outside of cell if not enough nodes were found in first run, but try to find almost all
                if (((double) rangeDijkstra.getFoundCellNodeSize()) / cellNodeCount < acceptedFullyReachablePercentage) {
                    rangeDijkstra = new RangeDijkstra(graph, weighting);
                    rangeDijkstra.setMaxVisitedNodes(getMaxCellNodesNumber() * eccentricityDijkstraLimitFactor);
                    rangeDijkstra.setEdgeFilter(edgeFilterSequence);
                    rangeDijkstra.setCellNodes(cellStorage.getNodesOfCell(isochroneNodeStorage.getCellId(node)));
                    rangeDijkstra.setAcceptedFullyReachablePercentage(acceptedFullyReachablePercentage);
                    edgeFilterSequence = new EdgeFilterSequence();
                    edgeFilterSequence.add(defaultEdgeFilter);
                    rangeDijkstra.setEdgeFilter(edgeFilterSequence);
                    eccentricity = rangeDijkstra.calcMaxWeight(node, relevantNodesSets.get(isochroneNodeStorage.getCellId(node)));
                }

                //TODO Maybe implement a logic smarter than having some high percentage for acceptedFullyReachable
                boolean isFullyReachable = ((double) rangeDijkstra.getFoundCellNodeSize()) / cellNodeCount >= acceptedFullyReachablePercentage;
                eccentricityStorage.setFullyReachable(node, isFullyReachable);

                eccentricityStorage.setEccentricity(node, eccentricity);
            }, String.valueOf(node));
        }

        threadPool.shutdown();

        try {
            for (int i = 0; i < borderNodeCount; i++) {
                completionService.take().get();
            }
        } catch (Exception e) {
            threadPool.shutdownNow();
            throw new RuntimeException(e);
        }

        eccentricityStorage.storeBorderNodeToPointerMap();
        eccentricityStorage.flush();
    }

    public void calcBorderNodeDistances(Weighting weighting, FlagEncoder flagEncoder) {
        if (borderNodeDistanceStorages == null) {
            borderNodeDistanceStorages = new ArrayList<>();
        }
        BorderNodeDistanceStorage borderNodeDistanceStorage = getBorderNodeDistanceStorage(weighting);
        if (!borderNodeDistanceStorage.loadExisting())
            borderNodeDistanceStorage.init();

        ExecutorService threadPool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(getMaxThreadCount(), Runtime.getRuntime().availableProcessors()));
        ExecutorCompletionService<String> completionService = new ExecutorCompletionService<>(threadPool);

        int cellCount = 0;
        for (IntCursor cellId : isochroneNodeStorage.getCellIds()) {
            final int currentCellId = cellId.value;
            cellCount++;
            completionService.submit(() -> calculateBorderNodeDistances(borderNodeDistanceStorage, currentCellId, weighting, flagEncoder), String.valueOf(currentCellId));
        }

        threadPool.shutdown();

        try {
            for (int i = 0; i < cellCount; i++) {
                completionService.take().get();
            }
        } catch (Exception e) {
            threadPool.shutdownNow();
            throw new RuntimeException(e);
        }
        borderNodeDistanceStorage.storeBorderNodeToPointerMap();
        borderNodeDistanceStorage.flush();
    }

    private void calculateBorderNodeDistances(BorderNodeDistanceStorage borderNodeDistanceStorage, int cellId, Weighting weighting, FlagEncoder flagEncoder) {
        int[] cellBorderNodes = getBorderNodesOfCell(cellId, cellStorage, isochroneNodeStorage).toArray();
        EdgeFilter defaultEdgeFilter = DefaultEdgeFilter.outEdges(flagEncoder);
        Graph graph = ghStorage.getBaseGraph();

        for (int borderNode : cellBorderNodes) {
            DijkstraOneToManyAlgorithm algorithm = new DijkstraOneToManyAlgorithm(graph, weighting, TraversalMode.NODE_BASED);
            algorithm.setEdgeFilter(defaultEdgeFilter);
            algorithm.prepare(new int[]{borderNode}, cellBorderNodes);
            algorithm.setMaxVisitedNodes(getMaxCellNodesNumber() * 20);
            SPTEntry[] targets = algorithm.calcPaths(borderNode, cellBorderNodes);
            int[] ids = new int[targets.length - 1];
            double[] distances = new double[targets.length - 1];
            int index = 0;
            for (int i = 0; i < targets.length; i++) {
                if(cellBorderNodes[i] == borderNode)
                    continue;
                ids[index] = cellBorderNodes[i];
                if (targets[i] == null) {
                    distances[index] = Double.POSITIVE_INFINITY;
                } else if (targets[i].adjNode == borderNode) {
                    distances[index] = 0;
                } else
                    distances[index] = targets[i].weight;
                index++;
            }
            borderNodeDistanceStorage.storeBorderNodeDistanceSet(borderNode, new BorderNodeDistanceSet(ids, distances));
        }
    }

    private IntHashSet getBorderNodesOfCell(int cellId, CellStorage cellStorage, IsochroneNodeStorage isochroneNodeStorage) {
        IntHashSet borderNodes = new IntHashSet();
        for (IntCursor node : cellStorage.getNodesOfCell(cellId)) {
            if (isochroneNodeStorage.getBorderness(node.value))
                borderNodes.add(node.value);
        }
        return borderNodes;
    }

    private IntHashSet getRelevantContourNodes(int cellId, CellStorage cellStorage, IsochroneNodeStorage isochroneNodeStorage) {
        if (this.locationIndex == null)
            return cellStorage.getNodesOfCell(cellId);
        List<Double> contourCoordinates = cellStorage.getCellContourOrder(cellId);
        FixedCellEdgeFilter fixedCellEdgeFilter = new FixedCellEdgeFilter(isochroneNodeStorage, cellId, Integer.MAX_VALUE);
        int j = 0;
        IntHashSet contourNodes = new IntHashSet();
        while (j < contourCoordinates.size()) {
            double latitude = contourCoordinates.get(j);
            j++;
            double longitude = contourCoordinates.get(j);
            j++;
            int nodeId = locationIndex.findClosest(latitude, longitude, fixedCellEdgeFilter).getClosestNode();
            contourNodes.add(nodeId);
        }
        return contourNodes;
    }
}