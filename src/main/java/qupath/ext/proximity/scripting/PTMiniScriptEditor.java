package qupath.ext.proximity.scripting;

import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.scripting.ScriptParameters;
import qupath.lib.gui.scripting.languages.ScriptLanguageProvider;
import java.util.function.*;
import javafx.scene.input.KeyCode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javax.script.ScriptException;

/**
 * Mini Groovy script editor GUI.
 */
public class PTMiniScriptEditor {

    private final String title;
    private final Stage stage = new Stage();
    private final Scene scene;
    /**
     * The text area to write the script.
     */
    private final TextArea textArea = new TextArea();
    /**
     * The console area. Currently only outputs errors.
     */
    private final TextArea consoleArea = new TextArea();
    private final Button confirmButton = new Button("Confirm");
    private final TextField nameField = new TextField();
    /**
     * Consumer to expose the script return object outside the class.
     */
    private Consumer<Object> resultCallback;
    /**
     * Predicate to validate the result output.
     */
    private Predicate<Object> resultValidationPredicate;
    /**
     * Predicate to validate the name.
     */
    private Predicate<String> nameValidationPredicate;
    private Function<Object, String> invalidResultMessage;
    private String invalidNameMessage;
    /**
     * Flag for script confirmation.
     */
    private boolean scriptConfirmed = false;
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    public PTMiniScriptEditor(String title) {
        this.title = title;
        this.stage.setTitle(title);

        Label nameLabel = new Label("Name: ");
        this.nameField.setPromptText("Enter name...");
        this.nameField.setPrefWidth(200);

        HBox nameBox = new HBox(5, nameLabel, this.nameField);
        nameBox.setPadding(new Insets(10, 10, 10, 10));
        nameBox.setAlignment(Pos.CENTER_LEFT);

        this.textArea.setPrefSize(500, 300);
        this.textArea.setPromptText("Enter Groovy script...");
        this.textArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 14px;");
        // Override TAB key
        this.textArea.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.TAB) {
                textArea.deletePreviousChar(); // remove the original tab character (which can't be consumed)
                textArea.replaceSelection("    "); // insert 4 spaces instead
                event.consume();
            }
        });
        // Autocomplete curly braces
        this.textArea.setOnKeyTyped(event -> {
            String character = event.getCharacter();
            if ("{".equals(character)) {
                int caretPos = textArea.getCaretPosition();
                textArea.insertText(caretPos, "}");
                textArea.positionCaret(caretPos);
            }
        });

        this.consoleArea.setEditable(false);
        this.consoleArea.setPrefHeight(150);
        this.consoleArea.setStyle("-fx-control-inner-background: white; -fx-text-fill: red; -fx-font-family: 'Courier New';");

        this.confirmButton.setMaxWidth(Double.MAX_VALUE);
        this.confirmButton.setOnAction(e -> {
            if (resultCallback == null) throw new IllegalStateException("Result callback must be set before execution.");

            Object result = executeScript(textArea.getText());
            if (result instanceof Throwable) return;

            if (!resultValidationPredicate.test(result)) {
                consoleArea.appendText("Invalid result: " + invalidResultMessage.apply(result));
                return;
            }

            if (!nameValidationPredicate.test(getNameField().getText())) {
                consoleArea.appendText("Invalid name: " + invalidNameMessage);
                return;
            }

            scriptConfirmed = true;
            resultCallback.accept(result);
            System.out.println("Result returned and scriptConfirmed = " + scriptConfirmed);
            System.out.println("Name to be applied = " + getNameField().getText());
            stage.close();
        });

        VBox layout = new VBox(nameBox, textArea, consoleArea, confirmButton);
//        layout.setPadding(new Insets(2, 5, 2, 5));

        VBox.setVgrow(textArea, Priority.ALWAYS);
        VBox.setVgrow(consoleArea, Priority.NEVER);

        this.scene = new Scene(layout, 500, 360);
        this.stage.setScene(scene);

        this.scene.addEventHandler(KeyEvent.KEY_PRESSED, closeWindowShortcut());
        this.scene.addEventHandler(KeyEvent.KEY_PRESSED, confirmScriptShortcut());

        stage.setOnHidden(e -> {
            if (!scriptConfirmed) {
                resultCallback.accept(null);
                System.out.println("Null returned");
            }
            scriptConfirmed = false;
//            textArea.clear(); // don't clear, as the user may want to keep what was last entered
            consoleArea.clear();
        });

    }

    public void setResultCallback(Consumer<Object> callback) {
        this.resultCallback = callback;
    }

    public void setResultValidationPredicate(Predicate<Object> validationPredicate, Function<Object, String> invalidResultMessage) {
        this.resultValidationPredicate = validationPredicate;
        this.invalidResultMessage = invalidResultMessage;
    }

    public void setResultValidationPredicate(Predicate<Object> validationPredicate, String invalidResultMessage) {
        setResultValidationPredicate(validationPredicate, unusedArg -> invalidResultMessage);
    }

    public void setNameValidationPredicate(Predicate<String> nameValidationPredicate, String invalidNameMessage) {
        this.nameValidationPredicate = nameValidationPredicate;
        this.invalidNameMessage = invalidNameMessage;
    }

    public Object executeScript(String script) {
        if (GroovyLanguage.getInstance() == null) {
            ScriptLanguageProvider.getAvailableLanguages(); // this activates QuPath's Groovy engine
        }

        // get QuPath's built-in Groovy
        var groovyLang = GroovyLanguage.getInstance();

        ScriptParameters params = ScriptParameters.builder()
                .setScript(getImportString() + script)
                .setSystemWriters()
                .build();

        // execute and get result
        try {
            consoleArea.clear();
            return groovyLang.execute(params);
        } catch (ScriptException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) { // get root problem
                cause = cause.getCause();
            }

            int extraLines = getImportString().replaceAll("[^\\n]", "").length();
            int line = e.getLineNumber();
            int adjustedLineNumber = line - extraLines;

            String message = cause.getLocalizedMessage();

            consoleArea.appendText("Error: " + message + " at line number " + adjustedLineNumber + "\n");
            return e;
        } catch (Throwable t) {
            consoleArea.appendText("Error: " + t.getMessage() + "\n");
            return t;
        }
    }

    private EventHandler<KeyEvent> closeWindowShortcut() {
        return event -> {
            if (((IS_WINDOWS && event.isControlDown()) || (!IS_WINDOWS && event.isMetaDown())) && event.getCode() == KeyCode.W) {
                stage.hide();
                event.consume();
            }
        };
    }

    private EventHandler<KeyEvent> confirmScriptShortcut() {
        return event -> {
            if (((IS_WINDOWS && event.isControlDown()) || (!IS_WINDOWS && event.isMetaDown())) && event.getCode() == KeyCode.ENTER) {
                confirmButton.fire();
                event.consume();
            }
        };
    }

    public String getTitle() {
        return title;
    }

    public Stage getStage() {
        return stage;
    }

    public Scene getScene() {
        return scene;
    }

    public TextArea getTextArea() {
        return textArea;
    }

    public TextField getNameField() {
        return nameField;
    }

    public static String getImportString() {
        return """
            
            import static qupath.lib.scripting.QP.*;
            import static qupath.lib.gui.scripting.QPEx.*;
            import qupath.lib.scripting.QP;
            import qupath.lib.gui.scripting.QPEx;

            """;
    }

}
