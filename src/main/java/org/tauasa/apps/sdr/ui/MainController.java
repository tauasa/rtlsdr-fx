package org.tauasa.apps.sdr.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.tauasa.apps.sdr.StageReadyEvent;
import org.tauasa.apps.sdr.config.SdrProperties;
import org.tauasa.apps.sdr.service.SdrService;
import org.tauasa.apps.sdr.service.SpectrumFrame;

import java.util.concurrent.atomic.AtomicReference;

/** Builds and drives the JavaFX UI. A Spring bean that reacts to the stage event. */
@Component
public final class MainController {

    private final SdrService sdr;
    private final SdrProperties props;
    private final AtomicReference<SpectrumFrame> latest = new AtomicReference<>();

    private SpectrumView spectrumView;
    private WaterfallView waterfallView;
    private Label statusLabel;
    private long lastSeqRendered = -1;

    private long fpsWindowStart;
    private int framesInWindow;
    private double displayFps;

    private final ToggleButton connectBtn = new ToggleButton("Connect");
    private ComboBox<String> sourceBox;
    private TextField hostField;
    private TextField portField;
    private TextField freqField;
    private ComboBox<Integer> rateBox;
    private CheckBox autoGain;
    private Slider gainSlider;

    public MainController(SdrService sdr, SdrProperties props) {
        this.sdr = sdr;
        this.props = props;
    }

    @EventListener
    public void onStageReady(StageReadyEvent event) {
        Stage stage = event.getStage();

        spectrumView = new SpectrumView(props.getMinDb(), props.getMaxDb());
        waterfallView = new WaterfallView(sdr.fftSize(), props.getWaterfallHeight(),
                props.getMinDb(), props.getMaxDb());

        Pane specPane = new Pane(spectrumView.getCanvas());
        Pane wfPane = new Pane(waterfallView.getCanvas());
        spectrumView.getCanvas().widthProperty().bind(specPane.widthProperty());
        spectrumView.getCanvas().heightProperty().bind(specPane.heightProperty());
        waterfallView.getCanvas().widthProperty().bind(wfPane.widthProperty());
        waterfallView.getCanvas().heightProperty().bind(wfPane.heightProperty());

        SplitPane split = new SplitPane(specPane, wfPane);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.42);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0b0e14;");
        root.setTop(buildToolbar());
        root.setCenter(split);
        root.setBottom(buildStatusBar());

        sdr.setFrameSink(latest::set);

        Scene scene = new Scene(root, 1100, 760);
        stage.setScene(scene);
        stage.setTitle("rtlsdr-fx \u2014 Lightweight RTL-SDR Receiver");
        stage.setOnCloseRequest(e -> sdr.stop());
        stage.show();

        startRenderLoop();
    }

    private Node buildToolbar() {
        sourceBox = new ComboBox<>();
        sourceBox.getItems().addAll("RTL-TCP", "Simulated");
        sourceBox.getSelectionModel().select("Simulated");

        hostField = new TextField(props.getHost());
        hostField.setPrefWidth(110);
        portField = new TextField(Integer.toString(props.getPort()));
        portField.setPrefWidth(60);

        freqField = new TextField(String.format("%.4f", props.getCenterFrequency() / 1e6));
        freqField.setPrefWidth(100);
        freqField.setOnAction(e -> applyFrequency());

        rateBox = new ComboBox<>();
        rateBox.getItems().addAll(250000, 1024000, 1536000, 1800000, 2048000, 2400000, 2560000, 3200000);
        rateBox.getSelectionModel().select(Integer.valueOf(props.getSampleRate()));
        rateBox.setOnAction(e -> {
            Integer r = rateBox.getValue();
            if (r != null) {
                sdr.setSampleRate(r);
            }
        });

        autoGain = new CheckBox("Auto gain");
        autoGain.setSelected(props.isAutoGain());
        autoGain.setTextFill(Color.web("#c7cedb"));
        autoGain.setOnAction(e -> {
            sdr.setAutoGain(autoGain.isSelected());
            gainSlider.setDisable(autoGain.isSelected());
        });

        gainSlider = new Slider(0, 49.6, props.getGainTenthsDb() / 10.0);
        gainSlider.setPrefWidth(140);
        gainSlider.setDisable(props.isAutoGain());
        gainSlider.valueProperty().addListener((o, a, b) -> sdr.setGain((int) Math.round(b.doubleValue() * 10)));

        Button tune = new Button("Tune");
        tune.setOnAction(e -> applyFrequency());

        connectBtn.setOnAction(e -> toggleConnect());

        HBox bar = new HBox(8,
                labeled("Source", sourceBox),
                labeled("Host", hostField),
                labeled("Port", portField),
                labeled("Freq (MHz)", freqField), bottomAlign(tune),
                labeled("Rate (sps)", rateBox),
                bottomAlign(autoGain),
                labeled("Gain (dB)", gainSlider),
                bottomAlign(connectBtn));
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.BOTTOM_LEFT);
        bar.setStyle("-fx-background-color: #11151f; -fx-border-color: #1d2230; -fx-border-width: 0 0 1 0;");
        return bar;
    }

    private VBox labeled(String text, Node node) {
        Label l = new Label(text);
        l.setTextFill(Color.web("#7f8aa0"));
        l.setStyle("-fx-font-size: 10;");
        return new VBox(2, l, node);
    }

    private VBox bottomAlign(Node node) {
        VBox box = new VBox(node);
        box.setAlignment(Pos.BOTTOM_LEFT);
        return box;
    }

    private Node buildStatusBar() {
        statusLabel = new Label("Ready. Pick a source and press Connect. (Simulated needs no hardware.)");
        statusLabel.setTextFill(Color.web("#9aa3b5"));
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setStyle("-fx-background-color: #11151f; -fx-border-color: #1d2230; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private void applyFrequency() {
        try {
            double mhz = Double.parseDouble(freqField.getText().trim());
            sdr.setCenterFrequency((long) (mhz * 1e6));
        } catch (NumberFormatException ignored) {
            // leave the previous frequency in place
        }
    }

    private void toggleConnect() {
        if (connectBtn.isSelected()) {
            try {
                applyFrequency();
                Integer r = rateBox.getValue();
                if (r != null) {
                    sdr.setSampleRate(r);
                }
                if ("RTL-TCP".equals(sourceBox.getValue())) {
                    sdr.startRtl(hostField.getText().trim(), Integer.parseInt(portField.getText().trim()));
                } else {
                    sdr.startSimulated();
                }
                if (!autoGain.isSelected()) {
                    sdr.setGain((int) Math.round(gainSlider.getValue() * 10));
                }
                connectBtn.setText("Disconnect");
                setControlsConnected(true);
            } catch (Exception ex) {
                connectBtn.setSelected(false);
                statusLabel.setText("Connect failed: " + ex.getMessage());
            }
        } else {
            sdr.stop();
            connectBtn.setText("Connect");
            setControlsConnected(false);
            statusLabel.setText("Stopped.");
        }
    }

    private void setControlsConnected(boolean connected) {
        sourceBox.setDisable(connected);
        hostField.setDisable(connected);
        portField.setDisable(connected);
    }

    private void startRenderLoop() {
        fpsWindowStart = System.nanoTime();
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                SpectrumFrame f = latest.get();
                if (f != null) {
                    boolean fresh = f.sequence() != lastSeqRendered;
                    spectrumView.render(f.powerDb(), f.centerFrequency(), f.sampleRate());
                    if (fresh) {
                        lastSeqRendered = f.sequence();
                        waterfallView.pushRow(f.powerDb());
                        framesInWindow++;
                    }
                }
                waterfallView.render();

                if (now - fpsWindowStart >= 1_000_000_000L) {
                    displayFps = framesInWindow * 1e9 / (now - fpsWindowStart);
                    framesInWindow = 0;
                    fpsWindowStart = now;
                    updateStatus(f);
                }
            }
        }.start();
    }

    private void updateStatus(SpectrumFrame f) {
        if (f == null) {
            statusLabel.setText(sdr.describe());
            return;
        }
        statusLabel.setText(String.format("%s  |  centre %.4f MHz  |  %.3f Msps  |  FFT %d  |  %.0f fps",
                sdr.describe(), f.centerFrequency() / 1e6, f.sampleRate() / 1e6, f.fftSize(), displayFps));
    }
}
