package org.tauasa.apps.sdr;

import javafx.application.HostServices;
import javafx.stage.Stage;
import org.springframework.context.ApplicationEvent;

/**
 * Published once the JavaFX primary {@link Stage} is available. Also carries the
 * {@link HostServices} handle so the UI can open external links (e.g. the
 * project's GitHub page) in the user's default browser.
 */
public class StageReadyEvent extends ApplicationEvent {

    private final transient HostServices hostServices;

    public StageReadyEvent(Stage stage, HostServices hostServices) {
        super(stage);
        this.hostServices = hostServices;
    }

    public Stage getStage() {
        return (Stage) getSource();
    }

    public HostServices getHostServices() {
        return hostServices;
    }
}
