package qupath.ext.proximity;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.proximity.aux.PTCellNeighborTracker;
import qupath.ext.proximity.aux.PTLineAnnotationTracker;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

/**
 * Main class to perform proximity analysis.
 */
public class PT2D {

    private final ImageData<BufferedImage> imageData; // better to keep the ImageData reference than to over-rely on the QP methods
    private final PathObjectHierarchy hierarchy;
    /**
     * The set of cells to analyze (i.e., target cells).
     * Set is used instead of List as it is more efficient and
     * duplicates are NOT expected.
     */
    private final Set<PathObject> anaCells;
    /**
     * The set of reference cells (i.e., cells to be tested against).
     * Set is used instead of List as it is more efficient and
     * duplicates are NOT expected.
     */
    private final Set<PathObject> refCells;
    /**
     * Key = the cell; Value = structure to store nearest neighbor data for the cell
     * (see implementation for {@link qupath.ext.proximity.aux.PTCellNeighborTracker})
     */
    private final Map<PathObject, PTCellNeighborTracker> anaCellsData = new ConcurrentHashMap<>();
    /**
     * Corresponds to the maximum number of interactions to test, used to define the array size of the
     * {@code globalCellMaps} field. If a maximum of n interactions are to be tested, mapSize should be n + 1
     * because determining cells that have exactly (non-cumulative) n interactions requires a subtractive
     * operation of (n + 1) - n (see implementation in {@code query()}).
     */
    private int mapSize = 10 + 1; // default
    /**
     * Array of TreeMap to be initialized of size mapSize. Retrieving the nth index of the array
     * will provide the map of cells that have n interactions.
     */
    private final TreeMap<Double, Set<PathObject>>[] globalCellMaps;
    /**
     * To store child (including grandchild and beyond) cells from {@code anaCells} for each TMA core,
     * if TMA option was used.
     */
    protected final Map<TMACoreObject, Set<PathObject>> tmaCoreAnaCellsMap = new ConcurrentHashMap<>();
    /**
     * To store child (including grandchild and beyond) cells from {@code refCells} for each TMA core,
     * if TMA option was used.
     */
    protected final Map<TMACoreObject, Set<PathObject>> tmaCoreRefCellsMap = new ConcurrentHashMap<>();
    /**
     * Structure to hold data for each line annotation (see implementation for
     * {@link qupath.ext.proximity.aux.PTLineAnnotationTracker})
     */
    private final Set<PTLineAnnotationTracker> lineAnnotationData = ConcurrentHashMap.newKeySet();
    private boolean labelsAdded = false;
    private boolean connectionsAdded = false;
    private double pixelSize;
    private ImagePlane plane;
    /**
     * Flag that may be changed asynchronously to terminate initialization. (Currently only for
     * GUI control.)
     */
    private AtomicBoolean terminationFlag = new AtomicBoolean(false);
    /**
     * To be set to {@code true} for the GUI:
     * Set up such that {@code query()} will ignore {@code removeInvisibleObjects()}
     * when showing connections, keeping invisible connections intact and
     * minimizing overhead.
     */
    protected boolean GUIControl = false;
    /**
     * Of limited use. To be set to true after the GUI (controller) prompts PT2D to clean up
     * without removing the PT2D instance. This will ensure the next query will properly
     * display any labels and connections (which may fail to happen if QP.fireHierarchyUpdate()
     * is not called).
     */
    protected boolean fireHierarchyUpdateFlag = false;
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    /**
     * Placeholder PathClass for line annotations (connections) not in display.
     */
    private static final PathClass hiddenPathClass = PathClass.fromString("PT2D_hidden_class");
    /**
     * String identifier to add to line annotations (connections) as metadata.
     */
    protected static final String lineMetadataKey = "PT2D_LINE";
    private static final Logger logger = LoggerFactory.getLogger(PT2D.class);
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public enum ComparisonType {
        EDGE, CENTROID
    }

    private ComparisonType comparisonType = ComparisonType.EDGE;

    public enum LineType {
        LINE, ARROW, DOUBLE_ARROW
    }

    private LineType lineType = LineType.LINE;

    public enum Mode {
        FULL_IMAGE, TMA
    }

    private Mode mode = Mode.FULL_IMAGE;

    private PT2D(PT2DBuilder builder) {
        this.imageData = builder.imageData;
        this.hierarchy = this.imageData.getHierarchy();
        this.anaCells = builder.cellsToAnalyze;
        this.refCells = builder.referenceCells;
        this.mapSize = builder.maxInteractionsToTest + 1;
        this.globalCellMaps = new TreeMap[mapSize];
        this.mode = builder.mode;
        this.comparisonType = builder.comparisonType;
        this.lineType = builder.lineType;
        this.terminationFlag = builder.terminationFlag;
        OverlayOptions overlayOptions = QPEx.getQuPath().getViewer().getOverlayOptions();
        overlayOptions.setPathClassHidden(hiddenPathClass, true);
        initialize();
    }

    public static class PT2DBuilder {
        private ImageData<BufferedImage> imageData = QP.getCurrentImageData();
        private Set<PathObject> cellsToAnalyze = Collections.emptySet();
        private Set<PathObject> referenceCells = Collections.emptySet();
        private int maxInteractionsToTest = 0;
        private Mode mode = Mode.FULL_IMAGE;
        private ComparisonType comparisonType = ComparisonType.EDGE;
        private LineType lineType = LineType.LINE;
        private AtomicBoolean terminationFlag = new AtomicBoolean(false);

        public PT2DBuilder setImageData(ImageData<BufferedImage> imageData) {
            this.imageData = imageData;
            return this;
        }

        public PT2DBuilder setCellsToAnalyze(Collection<PathObject> cellsToAnalyze) {
            this.cellsToAnalyze = new HashSet<>(cellsToAnalyze);
            if (this.cellsToAnalyze.size() != cellsToAnalyze.size()) {
                logger.warn("Duplicates removed in cells to analyze!");
            }
            return this;
        }

        public PT2DBuilder setReferenceCells(Collection<PathObject> referenceCells) {
            this.referenceCells = new HashSet<>(referenceCells);
            if (this.referenceCells.size() != referenceCells.size()) {
                logger.warn("Duplicates removed in reference cells!");
            }
            return this;
        }

        public PT2DBuilder setMaxInteractionsToTest(int maxInteractionsToTest) {
            this.maxInteractionsToTest = maxInteractionsToTest;
            return this;
        }

        public PT2DBuilder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public PT2DBuilder comparisonType(ComparisonType comparisonType) {
            this.comparisonType = comparisonType;
            return this;
        }

        public PT2DBuilder lineType(LineType lineType) {
            this.lineType = lineType;
            return this;
        }

        protected PT2DBuilder assignTerminationFlag(AtomicBoolean terminationFlag) {
            this.terminationFlag = terminationFlag;
            return this;
        }

        public PT2D build() {
            return new PT2D(this);
        }
    }

    /**
     * Create the PT2D instance without using the builder, using default class fields.
     * @param anaCells
     * @param refCells
     */
    public PT2D(Collection<PathObject> anaCells, Collection<PathObject> refCells) {
        this.imageData = QP.getCurrentImageData();
        this.hierarchy = this.imageData.getHierarchy();
        this.refCells = new HashSet<>(refCells);
        this.anaCells = new HashSet<>(anaCells);

        if (this.anaCells.size() != anaCells.size()) {
            logger.warn("Duplicates removed in cells to analyze!");
        }
        if (this.refCells.size() != refCells.size()) {
            logger.warn("Duplicates removed in reference cells!");
        }

        this.globalCellMaps = new TreeMap[mapSize];
        OverlayOptions overlayOptions = QPEx.getQuPath().getViewer().getOverlayOptions();
        overlayOptions.setPathClassHidden(hiddenPathClass, true);
        initialize();
    }

    /**
     * A one-time initialization of the PT2D instance involving R-tree generation and nearest-neighbor
     * calculations. Once completed, cell proximities can be efficiently queried any number of times
     * using the {@code query()} method.
     * @throws PT2DTerminationException
     */
    private void initialize() throws PT2DTerminationException {
        if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

        long start = System.currentTimeMillis();
        logger.info("Initializing PT2D instance ({})", this);

        setImageProperties(imageData);

        /*
        Populate these maps with all TMA cores ahead of time.
        This is because the code later will not put TMA cores that lack cell child objects, which will
        cause 'null' to return if you call get() on the map.

        The rationale behind tmaCoreAnaCellsMap and tmaCoreRefCellsMap is to allow cell objects that are not
        direct children (e.g., grandchildren) of cores to be counted (see later comments and code).
        This handling can be important in scenarios such as when a core's direct child object is an
        annotation that itself contains the cell objects.
         */
        tmaCoreAnaCellsMap.putAll(getTMACoreList(hierarchy).stream()
                .collect(Collectors.toMap(Function.identity(), v -> Collections.synchronizedSet(new HashSet<>()))));
        tmaCoreRefCellsMap.putAll(getTMACoreList(hierarchy).stream()
                .collect(Collectors.toMap(Function.identity(), v -> Collections.synchronizedSet(new HashSet<>()))));

        // build Rtree for refCells, depending on mode
        ConcurrentHashMap<TMACoreObject, STRtree> tmaRtreeMap = new ConcurrentHashMap<>(); // for TMA mode
        STRtree rtree = new STRtree(); // for full image mode

        long start_0 = System.currentTimeMillis();
        refCells.parallelStream().forEach(cell -> {

            if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

            Geometry cellGeom = cell.getROI().getGeometry();
            if (cellGeom == null) throw new IllegalStateException("One or more cell geometries are null!");

            switch (mode) {
                case TMA -> {
                    PathObject parent = cell; // temporary assignment
                    while (parent != null && !parent.isTMACore()) {
                    /* Assign TMA core to parent if found. This is needed in case the TMA is not
                    directly the cell's parent (e.g., grandparent).
                     */
                        parent = parent.getParent();
                    }
                    if (parent instanceof TMACoreObject) {
                        // For caching (see documentation at top).
                        tmaCoreRefCellsMap.get((TMACoreObject) parent).add(cell); // no need to computeIfAbsent; the map has been populated with all the cores

                        // for actually constructing the tree
                        tmaRtreeMap.computeIfAbsent((TMACoreObject) parent, k -> new STRtree());
                        synchronized (tmaRtreeMap.get(parent)) { // make rtree thread safe
                            tmaRtreeMap.get(parent).insert(cellGeom.getEnvelopeInternal(), cellGeom);
                        }
                    }
                }
                case FULL_IMAGE -> {
                    synchronized (rtree) {
                        rtree.insert(cellGeom.getEnvelopeInternal(), cellGeom);
                    }
                }
                default -> throw new IllegalStateException();
            }
        });

        if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

        // Explicitly build the R-trees. This should prevent any NullPointerExceptions when calling findNearestGeoms() later.
        switch (mode) {
            case TMA -> tmaRtreeMap.values().forEach(STRtree::build);
            case FULL_IMAGE -> rtree.build();
            default -> throw new IllegalStateException();
        }

        long end_0 = System.currentTimeMillis();
        logger.info("Time to make {} ({} trees): {} ms",
                this.mode == Mode.TMA ? "R-trees" : "R-tree",
                this.mode == Mode.TMA ? tmaRtreeMap.size() : 1,
                (end_0 - start_0));

        long start_1 = System.currentTimeMillis();
        this.anaCells.parallelStream().forEach(cell -> {

            if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

            Geometry cellGeom = cell.getROI().getGeometry();
            if (cellGeom == null) throw new IllegalStateException("One or more cell geometries are found to be null!");

            List<Geometry> nearestNeighbors;
            switch (mode) {
                case TMA -> {
                    PathObject parent = cell;
                    while (parent != null && !parent.isTMACore()) {
                        /* Assign TMA core to parent if found. This is needed in case the TMA is not
                        directly the cell's parent (e.g., grandparent).
                         */
                        parent = parent.getParent();
                    }
                    if (parent == null) { // keep this separate from block below b/c null does not work with containsKey()
                        nearestNeighbors = Collections.emptyList();
                    } else if (!tmaRtreeMap.containsKey(parent)) {
                        nearestNeighbors = Collections.emptyList();
                    } else {
                        // For caching (see documentation at top).
                        tmaCoreAnaCellsMap.get((TMACoreObject) parent).add(cell); // no need to computeIfAbsent; the map has been populated with all the cores

                        // for actually finding the nearest neighbors
                        nearestNeighbors = findNearestGeoms(cellGeom, tmaRtreeMap.get(parent), mapSize, comparisonType);
                    }
                }
                case FULL_IMAGE -> nearestNeighbors = findNearestGeoms(cellGeom, rtree, mapSize, comparisonType);
                default -> throw new IllegalStateException();
            }

            anaCellsData.put(cell, new PTCellNeighborTracker(cell));
            for (int i = 0; i < nearestNeighbors.size() /* same as mapSize (or smaller) */; i++) {
                double nearestDistance = (comparisonType == ComparisonType.EDGE)
                        ? cellGeom.distance(nearestNeighbors.get(i))*pixelSize
                        : cellGeom.getCentroid().distance(nearestNeighbors.get(i).getCentroid())*pixelSize;
                anaCellsData.get(cell).addData(i, nearestNeighbors.get(i), nearestDistance);
            }

        });

        if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

        /*
        Starting here, we create lines for the connections
         */

        switch (comparisonType) {
            case EDGE -> {
                anaCellsData.entrySet().parallelStream().forEach(entry -> {

                    if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

                    PathObject anaCell = entry.getKey();
                    ROI anaCellROI = anaCell.getROI();
                    PTCellNeighborTracker nearestNeighborData = entry.getValue();

                    Geometry anaCellGeom = anaCellROI.getGeometry();

                    for (Geometry geometry : nearestNeighborData.getGeometrySet()) {
                        Coordinate[] closestPoints = DistanceOp.nearestPoints(anaCellGeom, geometry);
                        Coordinate startPoint = closestPoints[0]; // Closest point on anaCell
                        Coordinate endPoint = closestPoints[1]; // Closest point on nearest neighbor

                        ROI lineROI = ROIs.createLineROI(startPoint.getX(), startPoint.getY(), endPoint.getX(), endPoint.getY(), plane);

                        PathObject lineAnnotation = PathObjects.createAnnotationObject(lineROI);
                        applyLineType(lineAnnotation, lineType); // mutate lineAnnotation
                        lineAnnotationData.add(new PTLineAnnotationTracker(lineAnnotation, nearestNeighborData.getDistanceByGeom(geometry), anaCell));
                    }
                });
            }
            case CENTROID -> {
                anaCellsData.entrySet().parallelStream().forEach(entry -> {

                    if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

                    PathObject anaCell = entry.getKey();
                    ROI anaCellROI = anaCell.getROI();
                    PTCellNeighborTracker nearestNeighborData = entry.getValue();

                    double cellCentroidX = anaCellROI.getCentroidX();
                    double cellCentroidY = anaCellROI.getCentroidY();

                    for (Geometry geometry : nearestNeighborData.getGeometrySet()) {
                        double geomCentroidX = geometry.getCentroid().getX();
                        double geomCentroidY = geometry.getCentroid().getY();

                        ROI lineROI = ROIs.createLineROI(cellCentroidX, cellCentroidY, geomCentroidX, geomCentroidY, plane);

                        PathObject lineAnnotation = PathObjects.createAnnotationObject(lineROI);
                        applyLineType(lineAnnotation, lineType); // mutate lineAnnotation
                        lineAnnotationData.add(new PTLineAnnotationTracker(lineAnnotation, nearestNeighborData.getDistanceByGeom(geometry), anaCell));
                    }
                });
            }
            default -> throw new IllegalStateException();
        }

        if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

        /*
        End of creating line objects
         */

        long end_1 = System.currentTimeMillis();
        logger.info("Time to calculate distances: " + (end_1 - start_1) + " ms");

        long start_2 = System.currentTimeMillis();
        IntStream.range(0, globalCellMaps.length) // safer way of multithreading
                .parallel()
                .forEach(index -> {

                    if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

                    TreeMap<Double, Set<PathObject>> tempCellMap = new TreeMap<>();

                    this.anaCells.forEach(cell -> {
                        Double distance = anaCellsData.get(cell).getDistanceByN(index);
                        if (distance != null) { // <- null will occur if the number of reference cells are fewer than mapSize
                            tempCellMap.computeIfAbsent(distance, k -> new HashSet<>()).add(cell);
                        } else {
                            tempCellMap.computeIfAbsent(Double.NaN, k -> new HashSet<>()).add(cell);
                        }
                    });

                    globalCellMaps[index] = tempCellMap;
                });

        if (terminationFlag.get()) throw new PT2DTerminationException("PT2D terminated");

        long end_2 = System.currentTimeMillis();
        logger.info("Time to make {} ({}): {} ms",
                (globalCellMaps.length > 1 ? "tree maps" : "tree map"),
                globalCellMaps.length,
                (end_2 - start_2));

        long end = System.currentTimeMillis();
        logger.info("TOTAL TIME TO INITIALIZE PT2D INSTANCE ({}): {} ms", this, (end - start));
    }

    private void setImageProperties(ImageData<BufferedImage> imageData) {
        var pixelCal = imageData.getServer().getPixelCalibration();
        pixelSize = ((double)pixelCal.getPixelHeight() + (double)pixelCal.getPixelWidth())/2;
        plane = QPEx.getCurrentViewer() == null ? ImagePlane.getPlane(0, 0) : QPEx.getCurrentViewer().getImagePlane();
    }

    private static List<TMACoreObject> getTMACoreList(PathObjectHierarchy hierarchy) {
        if (hierarchy == null || hierarchy.getTMAGrid() == null)
            return Collections.emptyList();
        return hierarchy.getTMAGrid().getTMACoreList();
    }

    /**
     * Get the list of nearest neighbors of an STRtree by passing in a geometry, STRtree, number of nearest neighbors
     * to get, and comparison type. This method should be thread safe.
     * @param geom
     * @param tree
     * @param k
     * @param comparisonType
     * @return the list of nearest neighbor geometries
     */
    private static List<Geometry> findNearestGeoms(Geometry geom, STRtree tree, int k, ComparisonType comparisonType) {
        Object[] nearestGeomsArray;

        // permit multithreading by default, but enforce single threading temporarily if an exception is caught
        lock.readLock().lock();
        try {
            nearestGeomsArray = callNearestNeighbor(geom, tree, k, comparisonType);
        } catch (Exception e) {
            lock.readLock().unlock();
            lock.writeLock().lock();
            logger.warn("Unexpected exception in R-Tree query. Reattempting operation...", e);

            try {
                tree.build(); // prompt to build the tree to resolve any issues there
                nearestGeomsArray = callNearestNeighbor(geom, tree, k, comparisonType); // reattempt the query
            } finally {
                lock.writeLock().unlock();
            }

            lock.readLock().lock();
        } finally {
            lock.readLock().unlock();
        }

        return Arrays.stream(nearestGeomsArray)
                .map(Geometry.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Get the array of nearest neighbors of an STRtree by passing in a geometry, STRtree, number of nearest neighbors
     * to get, and comparison type. According to the {@link STRtree} documentation, read operations are thread safe,
     * but for some reason, when multithreading, this method occasionally produces a NullPointerException despite
     * valid arguments. (This probably has to do with the STRtree not being built yet; explicitly calling {@code build()}
     * on the tree beforehand may help).
     * @param geom
     * @param tree
     * @param k
     * @param comparisonType
     * @return the array of nearest neighbor geometries
     * @throws NullPointerException
     */
    private static Object[] callNearestNeighbor(Geometry geom, STRtree tree, int k, ComparisonType comparisonType)
            throws NullPointerException {
        return tree.nearestNeighbour(geom.getEnvelopeInternal(), geom, (item1, item2) -> {
            Geometry g1 = (Geometry) item1.getItem();
            Geometry g2 = (Geometry) item2.getItem();

            Geometry o1 = (comparisonType == ComparisonType.EDGE) ? g1 : g1.getCentroid();
            Geometry o2 = (comparisonType == ComparisonType.EDGE) ? g2 : g2.getCentroid();

            return o1.distance(o2);
        }, k);
    }

    /**
     * Covert line annotations to a specified line type by altering their metadata.
     * @param line
     * @param lineType
     */
    private static void applyLineType(PathObject line, LineType lineType) {
        try {
            switch(lineType) {
                case LINE:
                    break;
                case ARROW:
                    line.getMetadata().put("arrowhead", ">");
                    break;
                case DOUBLE_ARROW:
                    line.getMetadata().put("arrowhead", "<>");
                    break;
                default:
                    throw new IllegalStateException("Invalid line type specified");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    class PT2DTerminationException extends RuntimeException {
        public PT2DTerminationException(String errorMessage) {
            super(errorMessage);
        }
    }

    /**
     * Core query method with full parameters and configurable display capabilities. This should be
     * thread safe if highlight, label, and connect are set to false.
     * @param distanceThreshold
     * @param noRefCells
     * @param highlight
     * @param label
     * @param connect
     * @param exclusive
     * @return the set of cells
     */
    private Set<PathObject> query(double distanceThreshold, int noRefCells, boolean highlight, boolean label, boolean connect, boolean exclusive)
            throws ArrayIndexOutOfBoundsException {

        if (noRefCells < 0) throw new IllegalArgumentException("# of reference cells cannot be negative!");
        if (label && !labelsAdded) promptToAddLabels();
        if (connect && !connectionsAdded) promptToAddConnections();

        TreeMap<Double, Set<PathObject>> cellMap;
        if (noRefCells == 0) {
            cellMap = new TreeMap<>() {{ put(0.0, anaCells); }}; // wrap anacells around a map basically
        } else {
            cellMap = globalCellMaps[noRefCells - 1]; // adjust for array index
        }

        if (cellMap == null || cellMap.isEmpty()) return Collections.emptySet();

        if (exclusive) {
            TreeMap<Double, Set<PathObject>> cellsToExcludeMap = globalCellMaps[noRefCells]; // adjust for array index
            Future<Set<PathObject>> toExclude = pool.submit(() -> {
                Set<PathObject> cellsToExclude = cellsToExcludeMap.headMap(distanceThreshold, true)
                        .values()
                        .stream()
                        .flatMap(Set::stream)
                        .collect(Collectors.toSet());
                return cellsToExclude;
            });

            Future<Set<PathObject>> withinDistance = pool.submit(() -> {
                Set<PathObject> cellsWithinDistance = cellMap.headMap(distanceThreshold, true)
                        .values()
                        .stream()
                        .flatMap(Set::stream)
                        .collect(Collectors.toSet());
                return cellsWithinDistance;
            });

            if (label) {
                for (int i = 0; i < mapSize; i++) {
                    int finalI = i;
                    exclusive().get(distanceThreshold, i).parallelStream().forEach(cell -> { // get non-cumulative amount
                        anaCellsData.get(cell).getCentroidPoint().setName(String.valueOf(finalI));
                    });
                }
                get(distanceThreshold, mapSize).parallelStream().forEach(cell -> { // for when number of interaction exceeds (mapSize - 1)
                    anaCellsData.get(cell).getCentroidPoint().setName(mapSize - 1 + "+");
                });
            }

            // Put this as far down as you can to leverage the futures...
            Set<PathObject> cellsToExclude;
            Set<PathObject> cellsWithinDistance;
            try {
                cellsToExclude = toExclude.get();
                cellsWithinDistance = withinDistance.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            cellsWithinDistance.removeAll(cellsToExclude);

            Set<PathObject> visible = ConcurrentHashMap.newKeySet();
            if (connect) {
                lineAnnotationData.parallelStream().forEach(data -> {
                    PathObject line = data.lineAnnotation();
                    PathObject cell = data.cell();
                    if (data.distance() <= distanceThreshold) {
                        line.setPathClass(cell.getPathClass());
                        if (highlight && cellsWithinDistance.contains(cell)) {
                            visible.add(line);
                        }
                    } else {
                        line.setPathClass(hiddenPathClass);
                    }
                });
                if (!GUIControl)
                    removeInvisibleObjects();
            }

            if (highlight) {
                visible.addAll(cellsWithinDistance);
                hierarchy.getSelectionModel().setSelectedObjects(visible, null);
            } else {
            /*
            This refreshes the hierarchy similar to QP.fireHierarchyUpdate() but is much faster.
             */
                hierarchy.getSelectionModel().setSelectedObject(null); // <- should be thread safe
            }
            return cellsWithinDistance;
        }

        Set<PathObject> cellsWithinDistance = cellMap.headMap(distanceThreshold, true)
                .values()
                .stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        if (label) {
            for (int i = 0; i < mapSize; i++) {
                int finalI = i;
                exclusive().get(distanceThreshold, i).parallelStream().forEach(cell -> { // get non-cumulative amount
                    anaCellsData.get(cell).getCentroidPoint().setName(String.valueOf(finalI));
                });
            }
            get(distanceThreshold, mapSize).parallelStream().forEach(cell -> {
                anaCellsData.get(cell).getCentroidPoint().setName(mapSize - 1 + "+");
            });
        }

        Set<PathObject> visible = ConcurrentHashMap.newKeySet();
        if (connect) {
            lineAnnotationData.parallelStream().forEach(data -> {
                PathObject line = data.lineAnnotation();
                PathObject cell = data.cell();
                if (data.distance() <= distanceThreshold) {
                    line.setPathClass(cell.getPathClass());
                    if (highlight && cellsWithinDistance.contains(cell)) {
                        visible.add(line);
                    }
                } else {
                    line.setPathClass(hiddenPathClass);
                }
            });
            if (!GUIControl)
                removeInvisibleObjects();
        }

        if (highlight) {
            visible.addAll(cellsWithinDistance);
            hierarchy.getSelectionModel().setSelectedObjects(visible, null);
        } else {
            /*
            This refreshes the hierarchy similar to QP.fireHierarchyUpdate() but is much faster.

            Also, this is probably redundant when label = true because in this case, query() would be
            recursively called with exclusive turned on, and that would do its own QP.selectObjects().
             */
            hierarchy.getSelectionModel().setSelectedObject(null); // <- should be thread safe
        }

        if (fireHierarchyUpdateFlag) {
            QP.fireHierarchyUpdate(hierarchy);
            logger.info("Hierarchy updated");
            fireHierarchyUpdateFlag = false;
        }

        return cellsWithinDistance;
    }

    /**
     * Query method (exclusive is set to false). This should be thread safe if highlight, label, and
     * connect are set to false.
     * @param distanceThreshold
     * @param noRefCells
     * @param highlight
     * @param label
     * @param connect
     * @return the set of cells
     */
    public Set<PathObject> query(double distanceThreshold, int noRefCells, boolean highlight, boolean label, boolean connect)
            throws ArrayIndexOutOfBoundsException {
        return query(distanceThreshold, noRefCells, highlight, label, connect, false);
    }

    /**
     * Get cells within distance threshold. This should be thread safe.
     * @param distanceThreshold
     * @param noRefCells
     * @return the set of cells
     */
    public Set<PathObject> get(double distanceThreshold, int noRefCells) {
        return query(distanceThreshold, noRefCells, false, false, false, false);
    }

    /**
     * Show (i.e., select) cells within distance threshold
     * @param distanceThreshold
     * @param noRefCells
     */
    public void show(double distanceThreshold, int noRefCells) {
        query(distanceThreshold, noRefCells, true, false, false, false);
    }

    /**
     * Overlay point objects labeling the number of interactions for each cell to be analyzed
     * @param distanceThreshold
     * @param noRefCells
     */
    public void label(double distanceThreshold, int noRefCells) {
        query(distanceThreshold, noRefCells, false, true, false, false);
    }

    /**
     * Overlay line annotations connecting the interactions for each cell to be analyzed.
     * Note: Max possible lines per cell limited by {@code mapSize}.
     * @param distanceThreshold
     * @param noRefCells
     */
    public void connect(double distanceThreshold, int noRefCells) {
        query(distanceThreshold, noRefCells, false, false, true, false);
    }

    /**
     * Add measurements. This should be thread safe as long as unique PathObjects are passed in.
     * @param pathObject the object (e.g., root object, TMA core, etc.) to which you want to add measurements
     * @param anaName name for cells to analyze
     * @param refName name for reference cells
     * @param anaSubset
     * @param refSubset
     * @param distanceThreshold
     */
    public void addMeasurements(PathObject pathObject,
                                String anaName,
                                String refName,
                                Collection<PathObject> anaSubset,
                                Collection<PathObject> refSubset,
                                double distanceThreshold) {

        String distanceThresholdFormatted = String.format("%.2f", distanceThreshold);

        MeasurementList objectMeasurementList = pathObject.getMeasurementList();

        Set<PathObject> anaCellsCommon = new HashSet<>(anaCells);
        if (anaSubset != null) {
            anaSubset = new HashSet<>(anaSubset); // reassign to remove any synchronization overhead from anaSubset
            anaCellsCommon.retainAll(anaSubset);
        }

        Set<PathObject> refCellsCommon = new HashSet<>(refCells);
        if (refSubset != null) {
            refSubset = new HashSet<>(refSubset); // reassign to remove any synchronization overhead from refSubset
            refCellsCommon.retainAll(refSubset);
        }

        objectMeasurementList.put("Total count of " + anaName, anaCellsCommon.size());
        objectMeasurementList.put("Total area (µm^2) of " + anaName,
                anaCellsCommon.stream().mapToDouble(cell -> cell.getROI().getArea()).sum()*pixelSize*pixelSize);

        objectMeasurementList.put("Total count of " + refName, refCellsCommon.size());
        objectMeasurementList.put("Total area (µm^2) of " + refName,
                refCellsCommon.stream().mapToDouble(cell -> cell.getROI().getArea()).sum()*pixelSize*pixelSize);

        /*
        Step 1: DO COUNTS
         */

        // Measure cumulative (1 or more interactions)
        Set<PathObject> cumulativeCellSet = get(distanceThreshold, 1);
        if (anaSubset != null)
            cumulativeCellSet.retainAll(anaSubset);
        objectMeasurementList.put("Count of '" + anaName + "' with 1 or more '" + refName + "' interactions" + " (≤ " + distanceThresholdFormatted + " µm)",
                cumulativeCellSet.size());

        // Measure exact # of interactions
        for (int i = 0; i < mapSize; i++) {
            Set<PathObject> cellSet = exclusive().get(distanceThreshold, i);
            if (anaSubset != null)
                cellSet.retainAll(anaSubset);
            objectMeasurementList.put("Count of '" + anaName + "' with exactly " + i + " '" + refName + "' "
                            + ((i == 1) ? "interaction" : "interactions") + " (≤ " + distanceThresholdFormatted + " µm)",
                    cellSet.size());
        }

        // Measure cells that exceed specified # of interactions
        Set<PathObject> excessCellSet = get(distanceThreshold, mapSize);
        if (anaSubset != null)
            excessCellSet.retainAll(anaSubset);
        objectMeasurementList.put("Count of '" + anaName + "' with more than " + (mapSize - 1) + " '" + refName + "' "
                        + ((mapSize - 1 == 1) ? "interaction" : "interactions") + " (≤ " + distanceThresholdFormatted + " µm)",
                excessCellSet.size());

        /*
        Step 2: DO AREAS
         */

        // Measure cumulative (1 or more interactions)
        objectMeasurementList.put("Area (µm^2) of '" + anaName + "' with 1 or more '" + refName + "' interactions" + " (≤ " + distanceThresholdFormatted + " µm)",
                cumulativeCellSet.stream().mapToDouble(cell -> cell.getROI().getArea()).sum()*pixelSize*pixelSize);

        // Measure exact # of interactions
        for (int i = 0; i < mapSize; i++) {
            Set<PathObject> cellList = exclusive().get(distanceThreshold, i);
            if (anaSubset != null)
                cellList.retainAll(anaSubset);
            objectMeasurementList.put("Area (µm^2) of '" + anaName + "' with exactly " + i + " '" + refName + "' "
                            + ((i == 1) ? "interaction" : "interactions") + " (≤ " + distanceThresholdFormatted + " µm)",
                    cellList.stream().mapToDouble(cell -> cell.getROI().getArea()).sum()*pixelSize*pixelSize);
        }

        // Measure cells that exceed specified # of interactions
        objectMeasurementList.put("Area (µm^2) of '" + anaName + "' with more than " + (mapSize - 1) + " '" + refName + "' "
                        + ((mapSize - 1 == 1) ? "interaction" : "interactions") + " (≤ " + distanceThresholdFormatted + " µm)",
                excessCellSet.stream().mapToDouble(cell -> cell.getROI().getArea()).sum()*pixelSize*pixelSize);

        /*
        Step 3: DO DESCRIPTIVE STATS
         */

        addDescriptiveStatsMeasurements(pathObject, anaName, refName, anaSubset);
        logger.info("Measurements added to " + pathObject);

    }

    /**
     * Add nearest neighbors distances to the analyzed (target) cells' measurement lists.
     * @param anaName name of the target cell population
     * @param refName name of the reference cell population
     * @param nameToAppend optional function to append a prefix to the measurement name for a given cell.
     *                     This is helpful if you want to stratify the cell measurement names by parent objects
     *                     like TMA cores, allowing these measurements to be treated separately when showing
     *                     detection measurements on QuPath.
     */
    public void addCellMeasurements(String anaName, String refName, Function<PathObject, String> nameToAppend) {

        String finalRefName = (refName == null) ? "reference" : refName;
        String finalAnaName = (anaName == null) ? "" : anaName;
        Function<PathObject, String> finalNameToAppend = (nameToAppend == null)
                ? pathObject -> ""
                : pathObject -> nameToAppend.apply(pathObject) + " ";

        anaCellsData.entrySet().parallelStream().forEach(entry -> {
            PathObject anaCell = entry.getKey();
            var anaCellMeasurementList = anaCell.getMeasurementList();

            Map<Integer, Double> doubleMap = entry.getValue().getIntDoubleNNMap();
            var doubleMapSorted = doubleMap.entrySet().stream()
                    .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                    .toList();

            doubleMapSorted.forEach(ent -> {
                anaCellMeasurementList.put(finalNameToAppend.apply(anaCell) + "This cell ('" + finalAnaName + "') to #"
                        + (ent.getKey() + 1) + " nearest '" + finalRefName + "' distance (µm)", ent.getValue());
            });
        });

        logger.info("Cell measurements added");
    }

    public void addCellMeasurements(String anaName, String refName) {
        addCellMeasurements(anaName, refName, null);
    }

    /**
     * Add descriptive statistics measurements for distances to nth nearest neighbors (e.g., mean,
     * median, Weibull parameters.)
     *
     * @param pathObject
     * @param anaName
     * @param refName
     * @param anaSubset
     */
    private void addDescriptiveStatsMeasurements(PathObject pathObject,
                                                String anaName,
                                                String refName,
                                                Collection<PathObject> anaSubset) {

        Map<PathObject, PTCellNeighborTracker> anaCellsMeasurementsCommon = new LinkedHashMap<>(anaCellsData);
        if (anaSubset != null) {
            anaCellsMeasurementsCommon.keySet().retainAll(anaSubset);
        }

        // Nearest neighbors
        for (int i = 0; i < mapSize; i++) {
            int finalI = i;

            // get list of nearest distances
            List<Double> nearestDistanceList = anaCellsMeasurementsCommon.entrySet().stream()
                    .map(entry -> entry.getValue().getDistanceByN(finalI))
                    .filter(Objects::nonNull) // filter out the nulls
                    .toList();

            // prepare statistics
            DescriptiveStatistics nearestDistanceStats = PTMath.getDescriptiveStatistics(nearestDistanceList);
            double[] shapeAndScale = null;
            try {
                shapeAndScale = PTMath.fitWeibull(nearestDistanceList);
            } catch (Exception e) {
                logger.warn("Unable to extract Weibull parameters for " + pathObject + ": " + e);
            }

            pathObject.getMeasurementList().put("'" + anaName + "': #" + (i + 1) + " nearest '" + refName + "' distance (μm): mean", nearestDistanceStats.getMean());
            pathObject.getMeasurementList().put("'" + anaName + "': #" + (i + 1) + " nearest '" + refName + "' distance (μm): median", nearestDistanceStats.getPercentile(50));
            pathObject.getMeasurementList().put("'" + anaName + "': #" + (i + 1) + " nearest '" + refName + "' distance (μm): standard deviation", nearestDistanceStats.getStandardDeviation());
            pathObject.getMeasurementList().put("'" + anaName + "': #" + (i + 1) + " nearest '" + refName + "' distance (μm): shape (Weibull parameter)",
                    shapeAndScale == null ? Double.NaN : shapeAndScale[0]);
            pathObject.getMeasurementList().put("'" + anaName + "': #" + (i + 1) + " nearest '" + refName + "' distance (μm): scale (Weibull parameter)",
                    shapeAndScale == null ? Double.NaN : shapeAndScale[1]);

        }

    }

    // For testing
    /**
     * Clear ALL PathObjects' measurement lists.
     */
    public static void clearAllMeasurements() {
        QP.getAllObjects().parallelStream().forEach(pathObject -> {
            pathObject.getMeasurementList().clear();
        });
    }

    protected void promptToAddLabels() {
        anaCellsData.entrySet().parallelStream().forEach(entry -> {
            PathObject anaCell = entry.getKey();
            PathObject pointObject = entry.getValue().getCentroidPoint();
            anaCell.addChildObject(pointObject);
            pointObject.setLocked(true);
            pointObject.setPathClass(anaCell.getPathClass());
        });
        labelsAdded = true;
    }

    protected void promptToAddConnections() {
        lineAnnotationData.parallelStream().forEach(data -> {
            PathObject line = data.lineAnnotation();
            PathObject anaCell = data.cell();
            anaCell.addChildObject(line);
            line.getMetadata().put(lineMetadataKey, null);
            line.setLocked(true);
            line.setPathClass(hiddenPathClass);
        });
        connectionsAdded = true;
    }

    protected void clearLabels() {
        hierarchy.removeObjects(anaCellsData.values().stream()
                .map(PTCellNeighborTracker::getCentroidPoint)
                .collect(Collectors.toSet()),
                false);
        labelsAdded = false;
    }

    protected void clearConnections() {
        hierarchy.removeObjects(lineAnnotationData.stream()
                .map(PTLineAnnotationTracker::lineAnnotation)
                .collect(Collectors.toSet()),
                false);
        connectionsAdded = false;
    }

    /**
     * Clear the entire display made by the PT2D instance by deselecting all objects, removing
     * line annotations (connections), and removing labels.
     */
    protected void cleanup() {
        clearConnections();
        clearLabels();
        hierarchy.getSelectionModel().clearSelection();
    }

    /**
     * Remove invisible objects added by the PT2D instance (currently this only involves removal of
     * invisible line annotations).
     */
    protected void removeInvisibleObjects() {
        hierarchy.removeObjects(lineAnnotationData.stream()
                .map(PTLineAnnotationTracker::lineAnnotation)
                .filter(line -> line.getPathClass() == hiddenPathClass)
                .collect(Collectors.toSet()),
                false);
    }

    /**
     * Get a helper object with shared methods as the PT2D instance with the {@code exclusive}
     * argument set as true.
     * @return a PT2DExclusive instance
     */
    public PT2DExclusive exclusive() {
        return new PT2DExclusive();
    }

    protected class PT2DExclusive {

        /**
         * Get, with the {@code exclusive} argument set as true. This should be thread safe.
         * @param distanceThreshold
         * @param noRefCells
         * @return
         */
        public Set<PathObject> get(double distanceThreshold, int noRefCells) {
            return PT2D.this.query(distanceThreshold, noRefCells, false, false, false, true);
        }

        /**
         * Show, with the {@code exclusive} argument set as true
         * @param distanceThreshold
         * @param noRefCells
         */
        public void show(double distanceThreshold, int noRefCells) {
            PT2D.this.query(distanceThreshold, noRefCells, true, false, false, true);
        }

        /**
         * Query, with the {@code exclusive} argument set as true. This should be thread safe if highlight, label,
         * and connect are set to false.
         * @param distanceThreshold
         * @param noRefCells
         * @param highlight
         * @param label
         * @param connect
         * @return
         */
        public Set<PathObject> query(double distanceThreshold,
                                            int noRefCells,
                                            boolean highlight,
                                            boolean label,
                                            boolean connect) {
            return PT2D.this.query(distanceThreshold, noRefCells, highlight, label, connect, true);
        }

    }

}
