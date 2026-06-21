package org.tauasa.apps.sdr;

import javafx.stage.Stage;
import org.springframework.context.ApplicationEvent;

/** Published once the JavaFX primary {@link Stage} is available. */
public class StageReadyEvent extends ApplicationEvent {

    public StageReadyEvent(Stage stage) {
        super(stage);
    }

    public Stage getStage() {
        return (Stage) getSource();
    }
}
