package qupath.ext.proximity;

import javafx.scene.input.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;

public class PTMainPanel implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(PTMainPanel.class);
    private final QuPathGUI qupath;
    private Stage stage;
    private Parent root;

    public PTMainPanel(final QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {

        if (stage == null){
            stage = new Stage();
            stage.initOwner(qupath.getStage());
            stage.setTitle("Cell proximity test");

            stage.setHeight(345);
            stage.setWidth(260);

            PTMainPanelController mainController = PTMainPanelController.getInstance(qupath, this);

            try {
                FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/PTMainPanel.fxml"));
                fxmlLoader.setController(mainController);
                root = fxmlLoader.load();

                Scene scene = new Scene(root);

                scene.setOnKeyPressed(PTKeyListener::keyPressed); // For testing purposes.
                qupath.getStage().getScene().addEventHandler(KeyEvent.KEY_PRESSED, PTKeyListener::keyPressed); // For testing purposes.
                scene.setOnKeyReleased(PTKeyListener::keyReleased); // For testing purposes.
                qupath.getStage().getScene().addEventHandler(KeyEvent.KEY_RELEASED, PTKeyListener::keyReleased); // For testing purposes.
                scene.addEventHandler(KeyEvent.KEY_PRESSED, mainController.showConnectionDistances()); // For testing purposes.
                qupath.getStage().getScene().addEventHandler(KeyEvent.KEY_PRESSED, mainController.showConnectionDistances()); // For testing purposes.
                scene.addEventHandler(KeyEvent.KEY_PRESSED, mainController.changeLineType()); // For testing purposes.
                qupath.getStage().getScene().addEventHandler(KeyEvent.KEY_PRESSED, mainController.changeLineType()); // For testing purposes.
//                scene.addEventHandler(KeyEvent.KEY_PRESSED, mainController.clearAllMeasurements()); // For testing purposes.
//                qupath.getStage().getScene().addEventHandler(KeyEvent.KEY_PRESSED, mainController.clearAllMeasurements()); // For testing purposes.

                stage.setScene(scene);

            } catch (Exception e) {
                logger.error("Problem with loading GUI: ", e);
            }

            stage.setOnHidden(e -> {
                mainController.stopPT2DRun.set(true);
                if (mainController.runPT2DAsync != null) {
                    mainController.runPT2DAsync.cancel(true); // this doesn't seem to work
                }
                mainController.cleanupUponExit();
                stage = null;
            });
            stage.show();

        } else {
            if (stage.isShowing()) {
                stage.toFront();
            }
        }
    }

    public Stage getStage() {
        return stage;
    }

    public Parent getRoot() {
        return root;
    }

}
