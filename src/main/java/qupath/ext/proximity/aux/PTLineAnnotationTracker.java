package qupath.ext.proximity.aux;

import qupath.lib.objects.PathObject;

/**
 * Data structure to work with {@link qupath.ext.proximity.PT2D} to store line annotations (connections)
 * for cells.
 */
public record PTLineAnnotationTracker(PathObject lineAnnotation, Double distance, PathObject cell) {}
