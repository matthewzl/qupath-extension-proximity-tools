package qupath.ext.proximity;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.Initializable;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.proximity.managers.PTMultiViewerManager;
import qupath.ext.proximity.scripting.PTMiniScriptEditor;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.application.Platform;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.scripting.QP;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PTMainPanelController implements Initializable {

    @FXML
    Label cellsToAnalyzeLabel;
    @FXML
    ComboBox<String> cellsToAnalyzeComboBox; // ComboBox is better than ChoiceBox
    @FXML
    Label refCellsLabel;
    @FXML
    ComboBox<String> referenceCellsComboBox; // ComboBox is better than ChoiceBox
    @FXML
    Label distanceThresholdLabel;
    @FXML
    TextField distanceThresholdTextField;
    @FXML
    Slider distanceThresholdSlider;
    @FXML
    Label noRefCellLabel;
    @FXML
    TextField noRefCellTextField;
    @FXML
    Slider noRefCellSlider;
    @FXML
    Button runButton;
    @FXML
    Button settingsButton;
    @FXML
    HBox refCellsHBox1;
    @FXML
    HBox refCellsHBox2;
    @FXML
    HBox refCellsHBox3;
    @FXML
    HBox sessionActiveHBox;
    @FXML
    Label sessionActiveLabel;

    ContextMenu ptGUIContextMenu = new ContextMenu();
    MenuItem lockMenuItem = new MenuItem("Lock changes...");
    MenuItem addMeasurementsMenuItem = new MenuItem("Add measurements...");
    CheckMenuItem highlightMenuItem = new CheckMenuItem("Show highlight...");
    CheckMenuItem labelMenuItem = new CheckMenuItem("Show labels...");
    CheckMenuItem connectMenuItem = new CheckMenuItem("Show connections...");
    MenuItem scriptContextMenuItem = new MenuItem("Create script...");
    CheckMenuItem advancedFeaturesMenuItem = new CheckMenuItem("Advanced features...");
    private String anaCellsAlias;
    private String refCellsAlias;
    private static final Logger logger = LoggerFactory.getLogger(PTMainPanelController.class);
    private final QuPathGUI qupath;
    private final double sliderMaxValueBasic = 10;
    private final double sliderMaxValueAdvanced = 1000;
    private final int refCellSliderMaxValue = 10;
    private PT2D pt2DInstance;
    protected CompletableFuture<Void> runPT2DAsync;
    protected final AtomicBoolean stopPT2DRun = new AtomicBoolean(false);
    private final BooleanProperty taskRunning = new SimpleBooleanProperty(false);
    private Set<String> pathClasses = new HashSet<>();
    private ImageData<BufferedImage> lastImageData;
    private static PTMainPanelController instance;
    private final PTMainPanel ptMainPanel;
    private PTMiniScriptEditor anaScriptEditor;
    private PTMiniScriptEditor refScriptEditor;
    private Collection<PathObject> anaCustomCollection = new ArrayList<>();
    private Collection<PathObject> refCustomCollection = new ArrayList<>();
    private String anaCustomConfirmedName;
    private String refCustomConfirmedName;


    // enforce singleton
    private PTMainPanelController(QuPathGUI qupath, PTMainPanel ptMainPanel) {
        this.qupath = qupath;
        this.lastImageData = qupath.getImageData(); // should be equivalent to QP.getCurrentImageData()
        this.ptMainPanel = ptMainPanel;
    }

    // enforce singleton
    public static synchronized PTMainPanelController getInstance(QuPathGUI qupath, PTMainPanel ptMainPanel) {
        if (instance == null) {
            instance = new PTMainPanelController(qupath, ptMainPanel);
        }
        return instance;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        // Hide this part of the GUI right now
        showSessionActive(false);

        // ENABLE LOGIC FOR COMBO BOX LISTENERS (AFFECTS HOW THEY UPDATE)
        // The class below will help the GUI adapt to one or more viewers.
        PTMultiViewerManager ptMultiViewerManager = new PTMultiViewerManager(qupath) {
            @Override
            public void hierarchyChanged(PathObjectHierarchyEvent event) {
                System.out.println("HIERARCHY CHANGED: Updating combo boxes...");
                updateComboBoxes(); // don't invalidateCustom() here because this method is called a lot
            }
            @Override
            public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld, ImageData<BufferedImage> imageDataNew) {
                System.out.println("IMAGE DATA CHANGED: Updating combo boxes...");
                if (imageDataOld != null && imageDataOld.getHierarchy() != null)
                    imageDataOld.getHierarchy().removeListener(this::hierarchyChanged);
                if (imageDataNew != null && imageDataNew.getHierarchy() != null)
                    imageDataNew.getHierarchy().addListener(this::hierarchyChanged);
                updateComboBoxes();
                invalidateCustom();
                stopPT2DRun.set(true);
                if (runPT2DAsync != null) {
                    runPT2DAsync.cancel(true); // this doesn't seem to work
                }
                nullifyPT2DInstance(true);
            }

            @Override // This is probably redundant
            public void viewerClosed(QuPathViewer viewer) {
                System.out.println("VIEWER CLOSED: Updating combo boxes...");
                updateComboBoxes();
                invalidateCustom();
                stopPT2DRun.set(true);
                if (runPT2DAsync != null) {
                    runPT2DAsync.cancel(true); // this doesn't seem to work
                }
                nullifyPT2DInstance(true);
            }

        };
        // This below is needed because it will detect mouse movements/clicks across viewers and determine if combo box updates are needed.
        ptMultiViewerManager.setCustomEvent(e -> {
            updateComboBoxes();
            // This is needed because it checks if the focus has changed (even if none of the images were changed among the viewers).
            // This exploits the fact that qupath.getImageData() retrieves the currently focused ImageData.
            if (lastImageData != qupath.getImageData()) {
                lastImageData = qupath.getImageData();
                invalidateCustom();
                stopPT2DRun.set(true);
                if (runPT2DAsync != null) {
                    runPT2DAsync.cancel(true); // this doesn't seem to work
                }
                nullifyPT2DInstance(true);
            }
        });

        // If a session is active and the combo boxes are changed, end the session
        cellsToAnalyzeComboBox.valueProperty().addListener((v, o, n) -> {
            if (n == null || !n.equals(o)) {
                nullifyPT2DInstance(true);
            }
        });
        referenceCellsComboBox.valueProperty().addListener((v, o, n) -> {
            if (n == null || !n.equals(o)) {
                nullifyPT2DInstance(true);
            }
        });

        // SETUP FOR THE SLIDER AND ITS ASSOCIATED TEXT FIELD
        distanceThresholdSlider.setMin(0);
        noRefCellSlider.setMin(0);
        distanceThresholdSlider.setMax(Math.log(sliderMaxValueBasic + 1)); // log slider
        noRefCellSlider.setMax(refCellSliderMaxValue);
        distanceThresholdSlider.setValue(Math.log(1 + 1));
        noRefCellSlider.setValue(1);
        // Allow the slider to return to the "1" position upon double-clicking
        distanceThresholdSlider.setOnMouseClicked(e -> {
            if (e.getButton().equals(MouseButton.PRIMARY)) {
                if (e.getClickCount() == 2) {
                    distanceThresholdSlider.setValue(Math.log(1 + 1));
                }
            }
        });
        noRefCellSlider.setOnMouseClicked(e -> {
            if (e.getButton().equals(MouseButton.PRIMARY)) {
                if (e.getClickCount() == 2) {
                    noRefCellSlider.setValue(1);
                }
            }
        });

        distanceThresholdSlider.valueProperty().addListener((v, o, n) -> {
            double linearValue = Math.exp(n.doubleValue()) - 1;
            distanceThresholdTextField.setText(String.format("%.2f", linearValue) + " µm");
        });
        noRefCellSlider.valueProperty().addListener((v, o, n) -> {
            noRefCellTextField.setText(String.valueOf((int)Math.round(n.doubleValue())));
        });
        noRefCellSlider.valueProperty().addListener((v, o, n) -> { // snapping mechanism
            noRefCellSlider.setValue((int)Math.round(n.doubleValue()));
        });
        noRefCellSlider.setBlockIncrement(1);
        noRefCellSlider.setMajorTickUnit(1);
        noRefCellSlider.setMinorTickCount(0);
        noRefCellSlider.setShowTickMarks(true);
        noRefCellSlider.setSnapToTicks(true);

        // Update slider when user clicks away from text field after inputting, or hits enter
        distanceThresholdTextField.focusedProperty().addListener((v, o, n) -> {
            if (!n) { // clicked away
                handleDistThreshTextFieldInput();
            }
        });
        distanceThresholdTextField.setOnAction(e -> handleDistThreshTextFieldInput());
        noRefCellTextField.focusedProperty().addListener((v, o, n) -> {
            if (!n) { // clicked away
                handleNoRefTextFieldInput();
            }
        });
        noRefCellTextField.setOnAction(e -> handleNoRefTextFieldInput());

        // Slider also has a listener so that whenever it changes, cells get highlighted
        distanceThresholdSlider.valueProperty().addListener((v, o, n) -> {
            if (pt2DInstance == null) {
                return;
            }
            double linearValue = Math.exp(n.doubleValue()) - 1;
            int refValue = (int)Math.round(noRefCellSlider.getValue());

            if (noRefCellLabel.getText().toLowerCase().contains("exclusive")) {
                pt2DInstance.exclusive().query(linearValue,
                        refValue,
                        highlightMenuItem.isSelected(),
                        labelMenuItem.isSelected(),
                        connectMenuItem.isSelected());
            } else {
                pt2DInstance.query(linearValue,
                        refValue,
                        highlightMenuItem.isSelected(),
                        labelMenuItem.isSelected(),
                        connectMenuItem.isSelected());
            }

        });
        noRefCellSlider.valueProperty().addListener((v, o, n) -> {
            if (pt2DInstance == null) {
                return;
            }
            int refValue = (int)Math.round(n.doubleValue());
            double linearValue = Math.exp(distanceThresholdSlider.getValue()) - 1;
            if (noRefCellLabel.getText().toLowerCase().contains("exclusive")) {
                pt2DInstance.exclusive().query(linearValue,
                        refValue,
                        highlightMenuItem.isSelected(),
                        labelMenuItem.isSelected(),
                        connectMenuItem.isSelected());
            } else {
                pt2DInstance.query(linearValue,
                        refValue,
                        highlightMenuItem.isSelected(),
                        labelMenuItem.isSelected(),
                        connectMenuItem.isSelected());
            }
        });

        // SPECIAL SETUP FOR THE #REFCELL TEXT LABEL
        noRefCellLabel.setOnMouseClicked(e -> {
            if (e.getButton().equals(MouseButton.PRIMARY) && (e.getClickCount() == 2)) {
                if (noRefCellLabel.getText().equals("# reference cells:")) {
                    noRefCellLabel.setText("[Exclusive] # reference cells:");
                } else {
                    noRefCellLabel.setText("# reference cells:");
                }

                refreshDisplay();
            }
        });


        // SETUP FOR THE RUN BUTTON
        // Only enable run button if both combo boxes have been populated
        runButton.disableProperty().bind(
                cellsToAnalyzeComboBox.getSelectionModel().selectedItemProperty().isNull()
                        .or(referenceCellsComboBox.getSelectionModel().selectedItemProperty().isNull())
                        .or(taskRunning)
        );
        // Set run button action
        runButton.setOnAction(e -> {

            stopPT2DRun.set(false);

            PT2D.ComparisonType connectionDisplay;
            PT2D.LineType lineDisplay;

            // below if-statements are for testing only (hidden feature)
//            System.out.println("Keys pressed " + PTKeyListener.getPressedKeys());
            if (PTKeyListener.areKeysPressed(KeyCode.T) && PTKeyListener.areKeysNotPressed(KeyCode.Y, KeyCode.U)) {
                connectionDisplay = PT2D.ComparisonType.CENTROID;
                lineDisplay = PT2D.LineType.LINE;
                Dialogs.showInfoNotification("Proximity Tools configuration",
                        "Setting comparison type to 'CENTROID' in this run");
            } else if (PTKeyListener.areKeysPressed(KeyCode.T, KeyCode.Y) && PTKeyListener.areKeysNotPressed(KeyCode.U)) {
                connectionDisplay = PT2D.ComparisonType.CENTROID;
                lineDisplay = PT2D.LineType.ARROW;
                Dialogs.showInfoNotification("Proximity Tools configuration",
                        "Setting comparison type to 'CENTROID' and line type to 'ARROW' in this run");
            } else if (PTKeyListener.areKeysPressed(KeyCode.T, KeyCode.U) && PTKeyListener.areKeysNotPressed(KeyCode.Y)) {
                connectionDisplay = PT2D.ComparisonType.CENTROID;
                lineDisplay = PT2D.LineType.DOUBLE_ARROW;
                Dialogs.showInfoNotification("Proximity Tools configuration",
                        "Setting comparison type to 'CENTROID' and line type to 'DOUBLE_ARROW' in this run");
            } else if (PTKeyListener.areKeysPressed(KeyCode.Y) && PTKeyListener.areKeysNotPressed(KeyCode.T, KeyCode.U)) {
                connectionDisplay = PT2D.ComparisonType.EDGE;
                lineDisplay = PT2D.LineType.ARROW;
                Dialogs.showInfoNotification("Proximity Tools configuration",
                        "Setting line type to 'ARROW' in this run");
            } else if (PTKeyListener.areOnlyKeysPressed(KeyCode.U) && PTKeyListener.areKeysNotPressed(KeyCode.T, KeyCode.Y)) {
                connectionDisplay = PT2D.ComparisonType.EDGE;
                lineDisplay = PT2D.LineType.DOUBLE_ARROW;
                Dialogs.showInfoNotification("Proximity Tools configuration",
                        "Setting line type to 'DOUBLE_ARROW' in this run");
            } else {
                connectionDisplay = PT2D.ComparisonType.EDGE;
                lineDisplay = PT2D.LineType.LINE;
            }
            PTKeyListener.clearPressedKeys(); // prompt listener to clear pressed keys as JavaFx sometimes causes keys to stay stuck

            nullifyPT2DInstance(true); // proactively remove the previous instance

            String mode;
            if (QP.getTMACoreList().size() > 0) {
                mode = Dialogs.showChoiceDialog("Select mode", "Test proximities by: ", new String[]{"TMA cores", "Full image"}, "TMA cores");
                if (mode == null)
                    return;
            } else {
                mode = "Full image";
            }

            anaCellsAlias = cellsToAnalyzeComboBox.getValue();
            refCellsAlias = referenceCellsComboBox.getValue();
            lockGUI();

            if (stopPT2DRun.get()) {
                logger.warn("Terminating run...");
                nullifyPT2DInstance(true);
                unlockGUI();
                return;
            }

            runPT2DAsync = CompletableFuture.runAsync(() -> {
                try {
                    Collection<PathObject> toAnalyzeCells = (cellsToAnalyzeComboBox.getValue() != null && cellsToAnalyzeComboBox.getValue().equals(anaCustomConfirmedName))
                            ? anaCustomCollection
                            : QP.getCellObjects().stream()
                            .filter(cell -> cell.getPathClass() == PathClass.fromString(cellsToAnalyzeComboBox.getValue())) // using .equals() might be better, but it risks NullPointerException
                            .toList();
                    Collection<PathObject> referenceCells = (referenceCellsComboBox.getValue() != null && referenceCellsComboBox.getValue().equals(refCustomConfirmedName))
                            ? refCustomCollection
                            : QP.getCellObjects().stream()
                            .filter(cell -> cell.getPathClass() == PathClass.fromString(referenceCellsComboBox.getValue())) // using .equals() might be better, but it risks NullPointerException
                            .toList();

                    if (stopPT2DRun.get() || Thread.interrupted()) {
                        logger.warn("Terminating run...");
                        nullifyPT2DInstance(true);
                        unlockGUI();
                        return;
                    }

                    try {
                        this.pt2DInstance = new PT2D.PT2DBuilder()
                                .setImageData(QP.getCurrentImageData())
                                .setCellsToAnalyze(toAnalyzeCells)
                                .setReferenceCells(referenceCells)
                                .setMaxInteractionsToTest(advancedFeaturesMenuItem.isSelected() ? refCellSliderMaxValue : 3)
                                .mode(mode.equals("TMA cores") ? PT2D.Mode.TMA : PT2D.Mode.FULL_IMAGE)
                                .comparisonType(connectionDisplay)
                                .lineType(lineDisplay)
                                .assignTerminationFlag(stopPT2DRun) // responsible for throwing PT2D.PT2DTerminationException
                                .build();
                    } catch (PT2D.PT2DTerminationException pte) {
                        logger.warn("PT2D initialization terminated...");
                    }

                    if (stopPT2DRun.get() || Thread.interrupted()) {
                        logger.warn("Terminating run...");
                        nullifyPT2DInstance(true);
                        unlockGUI();
                        return;
                    }

                    this.pt2DInstance.GUIControl = true;
                    refreshDisplay();
                    lockMenuItem.setDisable(false);
                    addMeasurementsMenuItem.setDisable(false);
                    Platform.runLater(() -> showSessionActive(true)); // using Platform.runLater() makes the appearance more in sync with the GUI unlocking
                } catch (Exception ex) {
                    logger.error("Error completing task: " + ex);
                    Dialogs.showErrorNotification("Error completing task", ex);
                }

                if (stopPT2DRun.get() || Thread.interrupted()) {
                    logger.warn("Terminating run...");
                    nullifyPT2DInstance(true);
                    unlockGUI();
                    return;
                }

                // Update the UI after task completion
                unlockGUI();
                QP.fireHierarchyUpdate();
                if (stopPT2DRun.get() || Thread.interrupted()) {
                    nullifyPT2DInstance(true);
                }
            });
        });

        // Bind script context menu item to run button
        scriptContextMenuItem.disableProperty().bind(runButton.disableProperty());
        lockMenuItem.setDisable(true);
        addMeasurementsMenuItem.setDisable(true);
        lockMenuItem.setOnAction(e -> {
            nullifyPT2DInstance(false);
        });
        addMeasurementsMenuItem.setOnAction(e -> {
            lockGUI();
            // ensure image data is later saved WITHOUT keeping any overlaid labels or connections
            pt2DInstance.cleanup();
            CompletableFuture<Void> addMeasurementsAsync = CompletableFuture.runAsync(() -> {
                try {
                    AtomicBoolean isCancelled = new AtomicBoolean(false);
                    String mode = "Full image"; // by default
                    if (QP.getTMACoreList().size() > 0) {
                        mode = Dialogs.showChoiceDialog("Add measurements", "Add measurements by: ", new String[]{"TMA cores", "Full image"}, "TMA cores");
                        if (mode == null) return;
                    }

//                    double linearValue = Math.exp(distanceThresholdSlider.getValue()) - 1; //
                    AddMeasurementsTask<?> addMeasurementsTask;
                    if (mode.equals("TMA cores")) {
                        addMeasurementsTask = new AddMeasurementsTask<>(QP.getTMACoreList(),
                                core -> pt2DInstance.tmaCoreAnaCellsMap.get(core),
                                core -> pt2DInstance.tmaCoreRefCellsMap.get(core),
                                cell -> {
                                    PathObject parent = cell; // see similar implementation in the PT2D initialize() method
                                    while (parent != null && !parent.isTMACore()) {
                                        parent = parent.getParent();
                                    }
                                    if (parent instanceof TMACoreObject)
                                        return "[" + parent.getName() + "]";
                                    else
                                        return "[null]";
                                });
                    } else {
                        addMeasurementsTask = new AddMeasurementsTask<>(Collections.singleton(QP.getCurrentHierarchy().getRootObject()));
                    }

                    Platform.runLater(() -> { // is Platform.runLater needed?
                        ProgressDialog progressDialog = new ProgressDialog(addMeasurementsTask);
                        progressDialog.initOwner(qupath.getStage());
                        progressDialog.setTitle("Adding measurements");
                        progressDialog.getDialogPane().setHeaderText("Adding measurements...");
                        progressDialog.getDialogPane().setGraphic(null);
                        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
                        progressDialog.getDialogPane().lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION, event -> {
                            if (Dialogs.showYesNoDialog("Cancel", "Are you sure you want to cancel adding measurements?")) {
                                addMeasurementsTask.quietCancel();
                                isCancelled.set(true);
                                progressDialog.setHeaderText("Cancelling...");
                                progressDialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
                            }
                            event.consume();
                        });
                    });

                    // Start the task
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    try {
                        CompletableFuture.runAsync(addMeasurementsTask, executor).get();
                        if (addMeasurementsTask.taskFailed) throw new RuntimeException(addMeasurementsTask.taskFailedException);
                    } catch (InterruptedException | ExecutionException ex) {
                        throw new RuntimeException(ex);
                    } finally {
                        executor.shutdown();
                    }

                    if (isCancelled.get()) return; // don't proceed to show final dialog box if cancelled

                    Dialogs.showMessageDialog("Measurements added", "Measurements added and saved!");

                } catch (Exception ex) {
                    Dialogs.showErrorNotification("Sorry, there was a problem adding measurements:", ex);
                } finally {
                    unlockGUI();
                    pt2DInstance.fireHierarchyUpdateFlag = true; // placing the flag here appears to work better
                }
            });
        });
        setDefaultMenuItemChoices();
        highlightMenuItem.setOnAction(e -> {
            if (pt2DInstance == null)
                return;
            QP.deselectAll();
            refreshDisplay();
        });
        labelMenuItem.setOnAction(e -> {
            if (pt2DInstance == null)
                return;
            pt2DInstance.clearLabels();
            refreshDisplay();
        });
        connectMenuItem.setOnAction(e -> {
            if (pt2DInstance == null)
                return;
            pt2DInstance.clearConnections();
            refreshDisplay();
        });
        scriptContextMenuItem.setOnAction(e -> {
            var editor = qupath.getScriptEditor();
            if (editor == null) {
                logger.error("No script editor is available!");
                return;
            }
            qupath.getScriptEditor().showScript("Template", getGeneratedTemplate());
        });

        // Hide refCells feature by default. Make it appear if 'advanced features' is turned on
        refCellsHBox1.setVisible(false);
        refCellsHBox2.setVisible(false);
        refCellsHBox3.setVisible(false);

        refCellsHBox1.setManaged(false);
        refCellsHBox2.setManaged(false);
        refCellsHBox3.setManaged(false);

        final double heightOffset = 65; // 65 works. Trying to call getHeight() from refCellsHBox1/2/3 gives 0.

        advancedFeaturesMenuItem.setOnAction(e -> {
            if (!advancedFeaturesMenuItem.isSelected()) {
                refCellsHBox1.setVisible(false);
                refCellsHBox2.setVisible(false);
                refCellsHBox3.setVisible(false);

                refCellsHBox1.setManaged(false);
                refCellsHBox2.setManaged(false);
                refCellsHBox3.setManaged(false);

                ptMainPanel.getStage().setHeight(ptMainPanel.getStage().getHeight() - heightOffset);
                distanceThresholdSlider.setMax(Math.log(sliderMaxValueBasic + 1));
                noRefCellLabel.setText("# reference cells:");
                noRefCellSlider.setValue(1);
                nullifyPT2DInstance(true);
            } else {
                refCellsHBox1.setVisible(true);
                refCellsHBox2.setVisible(true);
                refCellsHBox3.setVisible(true);

                refCellsHBox1.setManaged(true);
                refCellsHBox2.setManaged(true);
                refCellsHBox3.setManaged(true);

                ptMainPanel.getStage().setHeight(ptMainPanel.getStage().getHeight() + heightOffset);
                distanceThresholdSlider.setMax(Math.log(sliderMaxValueAdvanced + 1));
                nullifyPT2DInstance(true);
            }
        });

        ptGUIContextMenu.getItems().clear();
        ptGUIContextMenu.getItems().add(lockMenuItem);
        ptGUIContextMenu.getItems().add(addMeasurementsMenuItem);
        ptGUIContextMenu.getItems().add(new SeparatorMenuItem());
        ptGUIContextMenu.getItems().add(highlightMenuItem);
        ptGUIContextMenu.getItems().add(labelMenuItem);
        ptGUIContextMenu.getItems().add(connectMenuItem);
        ptGUIContextMenu.getItems().add(new SeparatorMenuItem());
        ptGUIContextMenu.getItems().add(scriptContextMenuItem);
        ptGUIContextMenu.getItems().add(new SeparatorMenuItem());
        ptGUIContextMenu.getItems().add(advancedFeaturesMenuItem);
        settingsButton.setContextMenu(ptGUIContextMenu); // show context menu on right click
        settingsButton.setOnAction(e -> ptGUIContextMenu.show(runButton, Side.RIGHT, 0, 0)); // show context menu on left click


        // Enable a SUPER secret option to allow custom definitions for cells to analyze and reference cells via scripting

//        // scratch the context menu idea
//        ContextMenu anaScriptContextMenu = new ContextMenu();
//        MenuItem anaScriptContextMenuItem = new MenuItem("Define custom");
//        anaScriptContextMenu.getItems().add(anaScriptContextMenuItem);
//        cellsToAnalyzeLabel.setContextMenu(anaScriptContextMenu);
//
//        ContextMenu refScriptContextMenu = new ContextMenu();
//        MenuItem refScriptContextMenuItem = new MenuItem("Define custom");
//        refScriptContextMenu.getItems().add(refScriptContextMenuItem);
//        refCellsLabel.setContextMenu(refScriptContextMenu);

        anaScriptEditor = new PTMiniScriptEditor("Cells to Analyze (Custom Definition)");
        refScriptEditor = new PTMiniScriptEditor("Reference Cells (Custom Definition)");

        anaScriptEditor.getStage().initOwner(ptMainPanel.getStage());
        refScriptEditor.getStage().initOwner(ptMainPanel.getStage());

        // Require the custom script editors only return Collection<PathObject>
        anaScriptEditor.setResultValidationPredicate(result -> result instanceof Collection<?>
                && !((Collection<?>) result).isEmpty()
                && ((Collection<?>) result).parallelStream().allMatch(item -> item instanceof PathObject),
                result -> "The return type must belong to Collection<PathObject> and not be empty!\n" +
                        "(Returned object: " + result + (result == null ? "" : " of "  + result.getClass()) + ")");

        refScriptEditor.setResultValidationPredicate(result -> result instanceof Collection<?>
                        && !((Collection<?>) result).isEmpty()
                        && ((Collection<?>) result).parallelStream().allMatch(item -> item instanceof PathObject),
                result -> "The return type must belong to Collection<PathObject> and not be empty!\n" +
                        "(Returned object: " + result + (result == null ? "" : " of "  + result.getClass()) + ")");

        // Enforce naming that does not coincide with existing combo box choices.
        anaScriptEditor.setNameValidationPredicate(name -> {
            return (!name.trim().equals("") && cellsToAnalyzeComboBox.getItems().stream().noneMatch(it -> it.trim().equals(name.trim())));
        }, "The name must not be blank or shared with any of the choice box names!");
        refScriptEditor.setNameValidationPredicate(name -> {
            return (!name.trim().equals("") && referenceCellsComboBox.getItems().stream().noneMatch(it -> it.trim().equals(name.trim())));
        }, "The name must not be blank or shared with any of the choice box names!");

        cellsToAnalyzeLabel.setOnMouseClicked(e -> {
            if (e.getButton().equals(MouseButton.PRIMARY) && (e.getClickCount() == 2)) {
                if (anaScriptEditor.getStage().isShowing()) return; // <- edge case
                lockGUI();
                String lastScriptContent = anaScriptEditor.getTextArea().getText();
                String lastName = anaScriptEditor.getNameField().getText();
                anaScriptEditor.setResultCallback(result -> {
                    unlockGUI();
                    if (result == null) { // null will happen by design when the script editor is exited (hidden)
                        if (cellsToAnalyzeComboBox.getValue() == null || !cellsToAnalyzeComboBox.getValue().equals(anaCustomConfirmedName)) {
                            return; // no need to proceed if custom was not confirmed
                        }
                        if (!lastScriptContent.equals(anaScriptEditor.getTextArea().getText())) {
                            cellsToAnalyzeComboBox.getSelectionModel().select(null);
                        }
                        if (!lastName.equals(anaScriptEditor.getNameField().getText())) {
                            cellsToAnalyzeComboBox.getSelectionModel().select(null);
                        }
                        // ^ if using custom has been confirmed, and the script gets altered and the user exits, clear the combo box

                        return;
                    }
                /*
                Below is the ONLY time anaCustomConfirmedName gets defined and selected by the cellsToAnalyzeComboBox,
                owing to the tight control upstream.
                 */
                    anaCustomCollection = new ArrayList<>((Collection<PathObject>) result); // store the collection ahead of time
                    anaCustomConfirmedName = anaScriptEditor.getNameField().getText();

                /*
                So ComboBox has this really nasty flaw (or feature?) in that when it is made to select an object that doesn't exist
                in its items list, it can fail to display the object name. The following is the best workaround I could find.
                 */
                    cellsToAnalyzeComboBox.getSelectionModel().select(null); // KEEP THIS OUTSIDE OF Platform.runLater
                    Platform.runLater(() -> { // Platform.runLater is NEEDED HERE
                        cellsToAnalyzeComboBox.getSelectionModel().select(null); // this helps with the UI refreshing
                        cellsToAnalyzeComboBox.getSelectionModel().select(anaCustomConfirmedName);
                    });
                });
                anaScriptEditor.getStage().showAndWait();
            }
        });

        refCellsLabel.setOnMouseClicked(e -> {
            if (e.getButton().equals(MouseButton.PRIMARY) && (e.getClickCount() == 2)) {
                if (refScriptEditor.getStage().isShowing()) return; // <- edge case
                lockGUI();
                String lastScriptContent = refScriptEditor.getTextArea().getText();
                String lastName = refScriptEditor.getNameField().getText();
                refScriptEditor.setResultCallback(result -> {
                    unlockGUI();
                    if (result == null) { // null will happen by design when the script editor is exited (hidden)
                        if (referenceCellsComboBox.getValue() == null || !referenceCellsComboBox.getValue().equals(refCustomConfirmedName)) {
                            return; // no need to proceed if custom was not confirmed
                        }
                        if (!lastScriptContent.equals(refScriptEditor.getTextArea().getText())) {
                            referenceCellsComboBox.getSelectionModel().select(null);
                        }
                        if (!lastName.equals(refScriptEditor.getNameField().getText())) {
                            referenceCellsComboBox.getSelectionModel().select(null);
                        }
                        // ^ if using custom has been confirmed, and the script gets altered and the user exits, clear the combo box

                        return;
                    }
                /*
                Below is the ONLY time refCustomConfirmedName gets defined and selected by the referenceCellsComboBox,
                owing to the tight control upstream.
                 */
                    refCustomCollection = new ArrayList<>((Collection<PathObject>) result); // store the collection ahead of time
                    refCustomConfirmedName = refScriptEditor.getNameField().getText();

                    // See earlier comment on the rationale for the code below
                    referenceCellsComboBox.getSelectionModel().select(null); // this helps with the UI refreshing
                    Platform.runLater(() -> { // Platform.runLater is NEEDED.
                        referenceCellsComboBox.getSelectionModel().select(null); // this helps with the UI refreshing
                        referenceCellsComboBox.getSelectionModel().select(refCustomConfirmedName);
                    });
                });
                refScriptEditor.getStage().showAndWait();
            }
        });

    }

    /**
     * EventHandler to toggle distance labels on connections made by PT2D.
     * For testing only (hidden feature)
     * @return the EventHandler
     */
    protected EventHandler<KeyEvent> showConnectionDistances() {
        return event -> {
//            if (pt2DInstance == null)
//                return;

            if (event.isShiftDown() && event.getCode() == KeyCode.N) {
                logger.info("Adding labels to connections");

                List<PathObject> connectionList = QP.getAllObjects().parallelStream()
                        .filter(pathObject -> pathObject.getMetadata().containsKey(PT2D.lineMetadataKey))
                        .filter(pathObject -> pathObject.getROI().isLine())
                        .toList();

                if (connectionList.stream().map(PathObject::getName).toList().contains(null)) {
                    var pixelCal = QP.getCurrentServer().getPixelCalibration();
                    double pixelSize = ((double)pixelCal.getPixelHeight() + (double)pixelCal.getPixelWidth())/2;
                    connectionList.parallelStream().forEach(pathObject -> pathObject.setName(String.format("%.2f",
                            pathObject.getROI().getLength()*pixelSize)));
                } else {
                    connectionList.parallelStream().forEach(pathObject -> pathObject.setName(null));
                }

                QP.fireHierarchyUpdate();
            }
        };
    }

    /**
     * EventHandler to change line type of connections made by PT2D.
     * For testing only (hidden feature)
     * @return the EventHandler
     */
    protected EventHandler<KeyEvent> changeLineType() {
        return event -> {
//            if (pt2DInstance == null)
//                return;

            String arrowType;
            if (event.isShiftDown() && event.getCode() == KeyCode.Y) {
                logger.info("Changing line type to ARROW...");
                arrowType = ">";
            } else if (event.isShiftDown() && event.getCode() == KeyCode.U) {
                logger.info("Changing line type to DOUBLE_ARROW...");
                arrowType = "<>";
            } else if (event.isShiftDown() && event.getCode() == KeyCode.I) {
                logger.info("Changing line type to LINE...");
                arrowType = null;
            } else {
                return;
            }

            QP.getAllObjects().parallelStream()
                    .filter(pathObject -> pathObject.getMetadata().containsKey(PT2D.lineMetadataKey))
                    .filter(pathObject -> pathObject.getROI().isLine())
                    .forEach(pathObject -> pathObject.getMetadata().put("arrowhead", arrowType));

            QP.fireHierarchyUpdate();
        };
    }

    /**
     * EventHandler to clear measurements for all objects.
     * For testing only (hidden feature)
     * @return the EventHandler
     */
    protected EventHandler<KeyEvent> clearAllMeasurements() {
        return event -> {
            if (event.isShiftDown() && event.getCode() == KeyCode.X) {
                logger.info("Clearing all measurements...");
                Dialogs.showInfoNotification("Proximity Tools",
                        "Clearing all measurements");
                PT2D.clearAllMeasurements();
            }
        };
    }

    private void handleDistThreshTextFieldInput() {
        String text = distanceThresholdTextField.getText()
                .trim()
                .replace("µm", "")
                .trim();
        try {
            double value = Double.parseDouble(text);
            if (value >= 0 && value <= sliderMaxValueAdvanced) {
                distanceThresholdSlider.setValue(Math.log(value + 1));
            } else if (value > sliderMaxValueAdvanced) {
                distanceThresholdSlider.setValue(Math.log(sliderMaxValueAdvanced + 1));
            } else if (value < 0) {
                distanceThresholdSlider.setValue(Math.log(0 + 1));
            }
        } catch (NumberFormatException e) {
            resetToDistThreshSliderValue();
        }
    }

    private void handleNoRefTextFieldInput() {
        String text = noRefCellTextField.getText().trim();
        try {
            double value = Double.parseDouble(text);
            if (value >= 0 && value <= refCellSliderMaxValue) {
                noRefCellSlider.setValue(value);
            } else if (value > refCellSliderMaxValue) {
                noRefCellSlider.setValue(refCellSliderMaxValue);
            } else if (value < 0) {
                noRefCellSlider.setValue(0);
            }
        } catch (NumberFormatException e) {
            resetToNoRefSliderValue();
        }
    }

    private void updateComboBoxes() {
        Set<String> newPathClasses = QP.getCellObjects().stream()
                .map(PathObject::getPathClass)
                .filter(Objects::nonNull)
                .map(PathClass::toString)
                .collect(Collectors.toSet());

        if (newPathClasses.equals(pathClasses)
                /* The below conditions are needed because they could be false when you close and reopen the GUI */
                && newPathClasses.equals(new HashSet<>(cellsToAnalyzeComboBox.getItems()))
                && newPathClasses.equals(new HashSet<>(referenceCellsComboBox.getItems()))) {
            return;
        }

        pathClasses = newPathClasses;

        /*
        Using Platform.runLater will avoid concurrency issues when changing hierarchies in the script editor
        while the GUI is open...
        */
        Platform.runLater(() -> {
            System.out.println("Updating combo boxes...");
            // Iterate through the two combo boxes...
            for (ComboBox<String> comboBox : new ComboBox[]{cellsToAnalyzeComboBox, referenceCellsComboBox}) {
                String comboBoxValue = comboBox.getValue(); // grab the value before clearing
                comboBox.getItems().clear();
                comboBox.getItems().addAll(pathClasses);

                if (pathClasses.contains(comboBoxValue))
                    comboBox.setValue(comboBoxValue);
            }

            /* Extreme edge-cases below, but if a new PathClass name coincides with the custom name,
            allow the PathClass name to take over and invalidate the ability to use the custom.
             */
            if (pathClasses.contains(anaCustomConfirmedName)) {
                logger.warn("Name for custom-defined cells to analyze clashes with newly populated choice options! " +
                        "Overriding the custom configuration...");
                anaCustomConfirmedName = null; // this is sufficient for the PathClass name to take over
            }
            if (pathClasses.contains(refCustomConfirmedName)) {
                logger.warn("Name for custom-defined reference cells clashes with newly populated choice options! " +
                        "Overriding the custom configuration...");
                refCustomConfirmedName = null; // this is sufficient for the PathClass name to take over
            }

        });
    }

    /**
     * Invalidate custom setup made in the combo boxes.
     */
    private void invalidateCustom() {
        if (cellsToAnalyzeComboBox.getValue() != null && cellsToAnalyzeComboBox.getValue().equals(anaCustomConfirmedName)) {
            cellsToAnalyzeComboBox.getSelectionModel().select(null);
        }
        if (referenceCellsComboBox.getValue() != null && referenceCellsComboBox.getValue().equals(refCustomConfirmedName)) {
            referenceCellsComboBox.getSelectionModel().select(null);
        }

        // and just to be extra complete, we'll clear these fields
        anaCustomConfirmedName = null;
        refCustomConfirmedName = null;
        anaCustomCollection = new ArrayList<>();
        refCustomCollection = new ArrayList<>();
    }

    private void nullifyPT2DInstance(boolean cleanup) { // TODO: rename to invalidatePT2DInstance()?
        if (pt2DInstance == null)
            return;

        if (cleanup) {
            this.pt2DInstance.cleanup();
        } else {
            this.pt2DInstance.removeInvisibleObjects();
        }

        lockMenuItem.setDisable(true);
        addMeasurementsMenuItem.setDisable(true);
        showSessionActive(false);
        this.pt2DInstance = null;
    }

    private void refreshDisplay() { // TODO: rename this to refreshQuery() or redoQuery()?
        if (pt2DInstance == null)
            return;

        double linearValue = Math.exp(distanceThresholdSlider.getValue()) - 1;
        int refValue = (int)Math.round(noRefCellSlider.getValue());

        if (noRefCellLabel.getText().toLowerCase().contains("exclusive")) {
            pt2DInstance.exclusive().query(linearValue,
                    refValue,
                    highlightMenuItem.isSelected(),
                    labelMenuItem.isSelected(),
                    connectMenuItem.isSelected());
        } else {
            pt2DInstance.query(linearValue,
                    refValue,
                    highlightMenuItem.isSelected(),
                    labelMenuItem.isSelected(),
                    connectMenuItem.isSelected());
        }

        QP.fireHierarchyUpdate(); // this might be redundant
    }

    private void lockGUI() {
        refCellsLabel.setDisable(true);
        cellsToAnalyzeLabel.setDisable(true);
        distanceThresholdLabel.setDisable(true);
        noRefCellLabel.setDisable(true);
        referenceCellsComboBox.setDisable(true);
        cellsToAnalyzeComboBox.setDisable(true);
        distanceThresholdTextField.setDisable(true);
        distanceThresholdSlider.setDisable(true);
        noRefCellTextField.setDisable(true);
        noRefCellSlider.setDisable(true);
        settingsButton.setDisable(true);
        taskRunning.set(true);
        runButton.setText("Please wait...");
    }

    private void unlockGUI() {
        Platform.runLater(() -> {
            refCellsLabel.setDisable(false);
            cellsToAnalyzeLabel.setDisable(false);
            distanceThresholdLabel.setDisable(false);
            noRefCellLabel.setDisable(false);
            referenceCellsComboBox.setDisable(false);
            cellsToAnalyzeComboBox.setDisable(false);
            distanceThresholdTextField.setDisable(false);
            distanceThresholdSlider.setDisable(false);
            noRefCellTextField.setDisable(false);
            noRefCellSlider.setDisable(false);
            settingsButton.setDisable(false);
            taskRunning.set(false);
            runButton.setText("Run");
        });
    }

    private void showSessionActive(boolean show) {
        if (sessionActiveHBox.isVisible() && sessionActiveHBox.isManaged() && show) return;
        if (!sessionActiveHBox.isVisible() && !sessionActiveHBox.isManaged() && !show) return;

        sessionActiveHBox.setVisible(show);
        sessionActiveHBox.setManaged(show);

        if (ptMainPanel.getStage() == null) return; // don't do anything if the GUI is hidden

        double heightOffset = show ? -sessionActiveHBox.getPrefHeight() : sessionActiveHBox.getPrefHeight();
        ptMainPanel.getStage().setHeight(ptMainPanel.getStage().getHeight() - heightOffset);
    }

    protected void cleanupUponExit() {
        nullifyPT2DInstance(true);
        setDefaultMenuItemChoices();
    }

    private void setDefaultMenuItemChoices() {
        highlightMenuItem.setSelected(true);
        labelMenuItem.setSelected(false);
        connectMenuItem.setSelected(false);
        advancedFeaturesMenuItem.setSelected(false);
    }

    private void resetToDistThreshSliderValue() {
        double linearValue = Math.exp(distanceThresholdSlider.getValue()) - 1;
        distanceThresholdTextField.setText(String.format("%.2f", linearValue) + " µm");
    }

    private void resetToNoRefSliderValue() {
        double linearValue = noRefCellSlider.getValue();
        noRefCellTextField.setText(String.valueOf((int)Math.round(linearValue)));
    }

    private String getGeneratedTemplate() {
        try (InputStream stream = QP.getTMACoreList().isEmpty()
                ? getClass().getClassLoader().getResourceAsStream("scripts/GeneratedTemplateFull.groovy")
                : getClass().getClassLoader().getResourceAsStream("scripts/GeneratedTemplateTMA.groovy")) {

            String rawScript = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            String editedScript0 = rawScript;
            boolean addCustomScriptImportsFlag = false;
            if (cellsToAnalyzeComboBox.getValue() != null && cellsToAnalyzeComboBox.getValue().equals(anaCustomConfirmedName)) {
                addCustomScriptImportsFlag = true;
                String customScriptText = anaScriptEditor.getTextArea().getText();
                String customScriptTextIndented = Arrays.stream(customScriptText.split("\n"))
                        .map(line -> "    " + line)
                        .collect(Collectors.joining("\n"));

                String customScriptTextFinal = """
                        Collection toAnalyzeCells = {
                        """ + customScriptTextIndented + """
                        \n}()
                        """;

                editedScript0 = editedScript0.replace("List toAnalyzeCells = getCellObjects().findAll { it.getPathClass() == getPathClass(\"[toAnalyze]\") }"
                        , customScriptTextFinal);
            }

            if (referenceCellsComboBox.getValue() != null && referenceCellsComboBox.getValue().equals(refCustomConfirmedName)) {
                addCustomScriptImportsFlag = true;
                String customScriptText = refScriptEditor.getTextArea().getText();
                String customScriptTextIndented = Arrays.stream(customScriptText.split("\n"))
                        .map(line -> "    " + line)
                        .collect(Collectors.joining("\n"));

                String customScriptTextFinal = """
                        Collection referenceCells = {
                        """ + customScriptTextIndented + """
                        \n}()
                        """;

                editedScript0 = editedScript0.replace("List referenceCells = getCellObjects().findAll { it.getPathClass() == getPathClass(\"[reference]\") }"
                        , customScriptTextFinal);
            }

            if (addCustomScriptImportsFlag) {
                editedScript0 = editedScript0 + "\n\n//Additional imports derived from custom scripting environment..." + PTMiniScriptEditor.getImportString();
            }

            String editedScript1 = editedScript0.replace("[toAnalyze]", cellsToAnalyzeComboBox.getValue())
                    .replace("[reference]", referenceCellsComboBox.getValue())
                    .replace("[distanceThreshold]", String.valueOf(Math.round((Math.exp(distanceThresholdSlider.getValue()) - 1) * 100.0) / 100.0))
                    .replace("[noRefCells]", String.valueOf((int)noRefCellSlider.getValue()))
                    .replace("[highlight]", String.valueOf(highlightMenuItem.isSelected()))
                    .replace("[label]", String.valueOf(labelMenuItem.isSelected()))
                    .replace("[connect]", String.valueOf(connectMenuItem.isSelected()));

            String editedScript2 = advancedFeaturesMenuItem.isSelected()
                    ? editedScript1.replace(".setMaxInteractionsToTest(3)", ".setMaxInteractionsToTest(" + refCellSliderMaxValue + ")")
                    : editedScript1;

            return noRefCellLabel.getText().toLowerCase().contains("exclusive") ? editedScript2 : editedScript2.replace(".exclusive()", "");

        } catch (IOException e) {
            logger.error(e.getLocalizedMessage(), e);
            return null;
        }
    }

    /**
     * Task to add measurements to one or more PathObjects (e.g., TMAs, root image, etc.)
     * @param <T>
     */
    class AddMeasurementsTask<T extends PathObject> extends Task<Void> {

        private boolean quietCancel = false;
        public boolean taskFailed = false;
        public Exception taskFailedException;
        /**
         * The batch of PathObjects to which to add measurements
         */
        private final Set<T> pathObjectBatch;
        /**
         * Function to define subsetting for cells to analyze
         */
        private final Function<PathObject, Collection<PathObject>> anaSubsetting;
        /**
         * Function to define subsetting for reference cells
         */
        private final Function<PathObject, Collection<PathObject>> refSubsetting;
        /**
         * Function to define the prefix to append to cell measurements
         */
        private final Function<PathObject, String> cellMeasurementPrefix;

        public void quietCancel() {
            this.quietCancel = true;
        }

        public boolean isQuietlyCancelled() {
            return quietCancel;
        }

        /**
         * Constructor
         * @param pathObjectBatch the batch of PathObjects (e.g., TMAs) to which to add measurements
         * @param anaSubsetting function to define subsetting for cells to analyze
         * @param refSubsetting function to define subsetting for reference cells
         * @param cellMeasurementPrefix function to define the prefix to append to cell measurements
         */
        public AddMeasurementsTask(Collection<T> pathObjectBatch,
                                   Function<PathObject, Collection<PathObject>> anaSubsetting,
                                   Function<PathObject, Collection<PathObject>> refSubsetting,
                                   Function<PathObject, String> cellMeasurementPrefix) {
            this.pathObjectBatch = new LinkedHashSet<>(pathObjectBatch); // use Set to remove duplicates
            this.anaSubsetting = anaSubsetting;
            this.refSubsetting = refSubsetting;
            this.cellMeasurementPrefix = cellMeasurementPrefix;
        }

        /**
         * Alternate constructor
         * @param pathObjectBatch the batch of PathObjects (e.g., TMAs) to which to add measurements
         */
        public AddMeasurementsTask(Collection<T> pathObjectBatch) {
            this(pathObjectBatch, null, null, null);
        }

        /**
         * Add measurements to the batch of PathObjects. The ImageData will be saved upon completion.
         * @return
         */
        @Override
        protected Void call() {
            try  {
                int batchSize = pathObjectBatch.size();

                // Multithreaded (should be thread safe)
                AtomicInteger atomicCounter = new AtomicInteger(0);
                Object lock = new Object();
                pathObjectBatch.parallelStream().forEach(pathObject -> {
                    if (isQuietlyCancelled() || isCancelled()) return;

                    synchronized (lock) {
                        updateProgress(atomicCounter.get(), batchSize);
                        int incrementedCount;
                        incrementedCount = atomicCounter.incrementAndGet();
                        updateMessage("Adding measurements (" + incrementedCount + "/" + batchSize + ")");
                    }

                    pt2DInstance.addMeasurements(pathObject,
                            anaCellsAlias,
                            refCellsAlias,
                            (anaSubsetting == null) ? null : anaSubsetting.apply(pathObject),
                            (refSubsetting == null) ? null : refSubsetting.apply(pathObject),
                            Math.exp(distanceThresholdSlider.getValue()) - 1);
                });

                if (isQuietlyCancelled() || isCancelled()) return null;

                updateProgress(batchSize - 0.5, batchSize);
                updateMessage("Adding cell measurements...");
                pt2DInstance.addCellMeasurements(anaCellsAlias, refCellsAlias, cellMeasurementPrefix);

                if (isQuietlyCancelled() || isCancelled()) return null;

                updateProgress(batchSize, batchSize);
                updateMessage("Saving...");
                QP.getProjectEntry().saveImageData(QP.getCurrentImageData());

            } catch (Exception e) {
                taskFailed = true;
                taskFailedException = e;
                throw new RuntimeException(e); // redundant
            }

            return null;
        }
    }

}
