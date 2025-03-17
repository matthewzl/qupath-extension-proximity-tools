package qupath.ext.proximity;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * For testing/debugging purposes right now.
 */
public class PTKeyListener {
    private static final Set<KeyCode> pressedKeys = new HashSet<>();
    private static long lastCheckTime = 0;
    private static Set<Window> lastFocusedWindows = Stage.getWindows().stream()
            .filter(Window::isFocused)
            .collect(Collectors.toSet());

    static {
        // clean up stuck keys periodically
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastCheckTime > 1000E6) { // every 1000 ms
                    cleanStuckKeys();
                    lastCheckTime = now;
                }
            }
        }.start();
    }

    public static void keyPressed(KeyEvent event) {
        pressedKeys.add(event.getCode());
        System.out.println("Key pressed, now:  " + pressedKeys);
    }

    public static void keyReleased(KeyEvent event) {
        pressedKeys.remove(event.getCode());
        System.out.println("Key pressed, now:  " + pressedKeys);
    }

    public static boolean isKeyPressed(KeyCode key) {
        return pressedKeys.contains(key);
    }

    public static boolean isCtrlPressed() {
        return pressedKeys.contains(KeyCode.CONTROL);
    }

    public static boolean isAltPressed() {
        return pressedKeys.contains(KeyCode.ALT);
    }

    public static boolean isShiftPressed() {
        return pressedKeys.contains(KeyCode.SHIFT);
    }

    public static boolean areKeysPressed(KeyCode... keys) {
        return pressedKeys.containsAll(Arrays.asList(keys));
    }

    public static boolean areKeysNotPressed(KeyCode... keys) {
        return Collections.disjoint(pressedKeys, Arrays.asList(keys));
    }

    public static boolean areOnlyKeysPressed(KeyCode... keys) {
        Set<KeyCode> expectedKeys = new HashSet<>(Arrays.asList(keys));
        return pressedKeys.equals(expectedKeys);
    }

    public static Set<KeyCode> getPressedKeys() {
        return pressedKeys;
    }

    public static void clearPressedKeys() {
        pressedKeys.clear();
    }

    private static void cleanStuckKeys() {
//        System.out.println("Test");

        // run in JavaFX thread
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(PTKeyListener::cleanStuckKeys);
            return;
        }

        Set<Window> currentFocusedWindows = Stage.getWindows().stream()
                .filter(Window::isFocused)
                .collect(Collectors.toSet());

        if (!lastFocusedWindows.equals(currentFocusedWindows)) {
            clearPressedKeys();
//            System.out.println("Focus changed, clearing all pressed keys.");
        }

        lastFocusedWindows = currentFocusedWindows;

    }

}
