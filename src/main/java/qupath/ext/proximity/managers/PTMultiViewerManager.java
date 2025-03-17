package qupath.ext.proximity.managers;

import javafx.event.EventHandler;
import javafx.scene.input.MouseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.scripting.QP;

// Make abstract?
public class PTMultiViewerManager implements PathObjectHierarchyListener, QuPathViewerListener {
    private static final Logger logger = LoggerFactory.getLogger(PTMultiViewerManager.class);
    private QuPathGUI qupath;
    private List<QuPathViewer> lastViewerList = new ArrayList<>();
    private EventHandler<? super MouseEvent> customEvent = e -> {};

    public PTMultiViewerManager(QuPathGUI qupath) {
        this.qupath = qupath;
        handleTrigger(); // Initialization...
    }

    public PTMultiViewerManager(QuPathGUI qupath, EventHandler<? super MouseEvent> customEvent) {
        this.qupath = qupath;
        this.customEvent = customEvent;
        handleTrigger(); // Initialization...
    }

    // This isn't a great setup, but it works...
    private void handleTrigger() {
        // If the # of viewers didn't change, don't do anything...
        // (Also designed to break out of the recursive call...)
        if (lastViewerList.equals(qupath.getAllViewers())) {
            logger.debug("Trigger: No viewers changed");
            return; // this escapes recursion
        }

        logger.debug("Trigger: Viewers changed");
        // Remove the event handlers and listeners to prevent memory leaks...
        for (QuPathViewer viewer : lastViewerList) {
            viewer.getView().setOnMouseClicked(null);
            viewer.removeViewerListener(this);
        }
        // Calling qupath.getAllViewers() will retrieve the new current list of viewers
        lastViewerList = new ArrayList<>(qupath.getAllViewers()); // Make a shallow copy
        for (QuPathViewer viewer : lastViewerList) {
            imageDataChanged(viewer, null, QP.getCurrentImageData());
            viewer.addViewerListener(this);

            var view = viewer.getView();
            view.setOnMouseClicked(e -> {
                handleTrigger();
                if (this.customEvent != null)
                    this.customEvent.handle(e);
            });
            view.setOnMouseExited(e -> {
                handleTrigger();
                if (this.customEvent != null)
                    this.customEvent.handle(e);
            });
            view.setOnMouseEntered(e -> {
                handleTrigger();
                if (this.customEvent != null)
                    this.customEvent.handle(e);
            });
        }
    }


    public void setCustomEvent(EventHandler<? super MouseEvent> customEvent) {
        this.customEvent = customEvent;
    }

    @Override
    public void hierarchyChanged(PathObjectHierarchyEvent event) {

    }

    @Override
    public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld, ImageData<BufferedImage> imageDataNew) {

    }

    @Override
    public void visibleRegionChanged(QuPathViewer viewer, Shape shape) {

    }

    @Override
    public void selectedObjectChanged(QuPathViewer viewer, PathObject pathObjectSelected) {

    }

    @Override
    public void viewerClosed(QuPathViewer viewer) {

    }

}
