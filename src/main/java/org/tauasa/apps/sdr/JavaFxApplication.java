package org.tauasa.apps.sdr;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Bridges JavaFX and Spring. The Spring context is started during
 * {@link #init()} (off the FX thread); when {@link #start(Stage)} fires on the
 * FX Application Thread we publish a {@link StageReadyEvent} that the UI bean
 * listens for. This keeps all UI construction on the correct thread while still
 * letting controllers be ordinary Spring beans with injected services.
 */
public class JavaFxApplication extends Application {

    private ConfigurableApplicationContext context;

    @Override
    public void init() {
        String[] args = getParameters().getRaw().toArray(new String[0]);
        this.context = new SpringApplicationBuilder(SdrApplication.class)
                .headless(false)          // JavaFX needs a non-headless AWT/Glass toolkit
                .run(args);
    }

    @Override
    public void start(Stage stage) {
        context.publishEvent(new StageReadyEvent(stage));
    }

    @Override
    public void stop() {
        context.close();
        Platform.exit();
    }
}
