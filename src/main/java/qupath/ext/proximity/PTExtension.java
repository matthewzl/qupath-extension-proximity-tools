package qupath.ext.proximity;

import javafx.scene.control.MenuItem;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.extensions.GitHubProject;

public class PTExtension implements QuPathExtension, GitHubProject {

    public static final String VERSION = "0.0.1";
    private PTMainPanel mainPanel;


    @Override
    public void installExtension(QuPathGUI qupath) {
        mainPanel = new PTMainPanel(qupath);

        var menu = qupath.getMenu("Extensions>Proximity Tools", true);
        MenuItem menuItem = new MenuItem("Test cell proximities");
        menuItem.setOnAction(e -> {
            mainPanel.run();
        });
        menu.getItems().add(menuItem);
    }

    @Override
    public String getName() {
        return "Proximity Tools extension";
    }

    @Override
    public String getDescription() {
        return "Identify classified cells by their distances to other classified cells \n\n"
                + "Version " + VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "matthewzl", "qupath-extension-proximity-tools");
    }

}