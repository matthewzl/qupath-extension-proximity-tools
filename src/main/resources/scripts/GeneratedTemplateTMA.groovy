import qupath.ext.proximity.PT2D
import qupath.lib.objects.TMACoreObject
import static qupath.lib.scripting.QP.*

// Define target and reference cell populations
List toAnalyzeCells = getCellObjects().findAll { it.getPathClass() == getPathClass("[toAnalyze]") }
List referenceCells = getCellObjects().findAll { it.getPathClass() == getPathClass("[reference]") }

// Create the PT2D object to test cell interactions
PT2D pt = new PT2D.PT2DBuilder()
        .setCellsToAnalyze(toAnalyzeCells) // set cells to analyze (i.e., target cells)
        .setReferenceCells(referenceCells) // set reference cells
        .setMaxInteractionsToTest(3) // set max number of interactions to test (i.e., nearest neighbor search depth)
        .mode(PT2D.Mode.TMA) // 'FULL_IMAGE' or 'TMA'
        .comparisonType(PT2D.ComparisonType.EDGE) // 'EDGE' or 'CENTROID'
        .lineType(PT2D.LineType.LINE) // 'LINE', 'ARROW', or 'DOUBLE_ARROW' for displayed connections. Does NOT affect calculations.
        .build()

// Query and display cell interactions
pt.exclusive().query(
        [distanceThreshold], // distance threshold
        [noRefCells], // # reference cells
        [highlight], // highlight
        [label], // show labels
        [connect] // show connections
)


// Uncomment below to add measurements

/* OPTION 1: Add measurements by TMA */
//getTMACoreList().parallelStream().forEach { core ->
//    pt.addMeasurements(
//            core, // TMA core object
//            "[toAnalyze]", // name for cells to analyze
//            "[reference]", // name for reference cells
//            pt.tmaCoreAnaCellsMap.get(core), // limit cells to analyze to a subset
//            pt.tmaCoreRefCellsMap.get(core), // limit reference cells to a subset
//            [distanceThreshold] // distance threshold
//    )
//}
//pt.addCellMeasurements("[toAnalyze]", "[reference]", cell -> {
//    def parent = cell
//    while (parent != null && !parent.isTMACore()) {
//        parent = parent.getParent()
//    }
//    if (parent instanceof TMACoreObject)
//        return "[" + parent.getName() + "]"
//    else
//        return "[null]"
//})

/* OPTION 2: Add measurements by full image */
//pt.addMeasurements(
//        getCurrentHierarchy().getRootObject(), // root object (i.e., image)
//        "[toAnalyze]", // name for cells to analyze
//        "[reference]", // name for reference cells
//        null, // limit cells to analyze to a subset
//        null, // limit reference cells to a subset
//        [distanceThreshold] // distance threshold
//)
//pt.addCellMeasurements("[toAnalyze]", "[reference]")
