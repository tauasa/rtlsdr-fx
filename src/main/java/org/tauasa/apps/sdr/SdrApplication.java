package org.tauasa.apps.sdr;

import javafx.application.Application;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point. Hands off to JavaFX, which boots the Spring
 * context in its {@code init()} phase.
 */
@SpringBootApplication
public class SdrApplication {

    public static void main(String[] args) {
        Application.launch(JavaFxApplication.class, args);
    }
}
