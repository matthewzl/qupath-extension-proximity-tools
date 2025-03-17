package qupath.ext.proximity.aux;

import org.locationtech.jts.geom.Geometry;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.util.*;

/**
 * Data structure to work with {@link qupath.ext.proximity.PT2D} to store nearest neighbor
 * data for cells.
 */
public class PTCellNeighborTracker {

    /**
     * The cell object representing the data
     */
    private final PathObject cell;
    /**
     * Integer-Double Nearest Neighbors Map.<br>
     * Key = n;
     * Value = distance to nth nearest neighbor
     */
    private final Map<Integer, Double> intDoubleNNMap = new HashMap<>();
    /**
     * Geometry-Double Nearest Neighbors Map.<br>
     * Key = geometry;
     * Value = distance to geometry
     */
    private final Map<Geometry, Double> geomDoubleNNMap = new HashMap<>();
    /**
     * Point object at the cell's centroid, which can be used to display labels.
     */
    private final PathObject centroidPoint;

    /**
     * Constructor
     * @param cell the cell object representing the data to be added
     */
    public PTCellNeighborTracker(PathObject cell) {
        this.cell = cell;
        ROI roi = ROIs.createLineROI(cell.getROI().getCentroidX(), cell.getROI().getCentroidY(), QPEx.getCurrentViewer().getImagePlane()); // a 0-length line looks better than a point
        PathObject pointObject = PathObjects.createAnnotationObject(roi);
        this.centroidPoint = pointObject;
    }

    /**
     * Add nearest neighbor data to the tracker.
     * WARNING: for efficiency, this method will not check if existing keys in
     * {@code nearestNeighborsByN} and {@code nearestNeighborsByGeom} are being
     * overridden, which may desync the maps.
     *
     * @param n representing nth nearest neighbor
     * @param geometry geometry of the nth nearest neighbor
     * @param distance distance to the nth nearest neighbor
     */
    public synchronized void addData(int n, Geometry geometry, double distance) { // synchronize to ensure thread safety
        intDoubleNNMap.put(n, distance);
        geomDoubleNNMap.put(geometry, distance);
    }

    /**
     * Get the unmodifiable set of nearest geometries.
     * @return unmodifiable set of nearest geometries
     */
    public Set<Geometry> getGeometrySet() {
        return Collections.unmodifiableSet(geomDoubleNNMap.keySet());
    }

    /**
     * Get the distance to the nth nearest neighbor.
     * @param n
     * @return distance to the nth nearest neighbor.
     * Null will be returned if the nth nearest neighbor does not exist.
     */
    public Double getDistanceByN(int n) {
        return intDoubleNNMap.get(n);
    }

    /**
     * Get the distance to a given geometry.
     * @param geometry the geometry
     * @return distance to the geometry.
     * Null will be returned if the geometry does not exist.
     */
    public Double getDistanceByGeom(Geometry geometry) {
        return geomDoubleNNMap.get(geometry);
    }

    public Map<Integer, Double> getIntDoubleNNMap() {
        return Collections.unmodifiableMap(intDoubleNNMap);
    }

    public Map<Geometry, Double> getGeomDoubleNNMap() {
        return Collections.unmodifiableMap(geomDoubleNNMap);
    }

    public PathObject getCell() {
        return cell;
    }

    public PathObject getCentroidPoint() {
        return centroidPoint;
    }
}
