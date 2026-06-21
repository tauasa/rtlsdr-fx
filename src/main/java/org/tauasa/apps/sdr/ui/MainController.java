package org.tauasa.apps.sdr.ui;

import javafx.animation.AnimationTimer;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.tauasa.apps.sdr.StageReadyEvent;
import org.tauasa.apps.sdr.config.SdrProperties;
import org.tauasa.apps.sdr.dsp.Demodulator;
import org.tauasa.apps.sdr.service.SdrService;
import org.tauasa.apps.sdr.service.SpectrumFrame;

import java.util.concurrent.atomic.AtomicReference;

/** Builds and drives the JavaFX UI. A Spring bean that reacts to the stage event. */
@Component
public final class MainController {

    private final SdrService sdr;
    private final SdrProperties props;
    private final AtomicReference<SpectrumFrame> latest = new AtomicReference<>();

    private Stage stage;
    private HostServices hostServices;

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
    private ComboBox<Demodulator.Mode> modeBox;
    private ToggleButton audioBtn;
    private Slider volumeSlider;

    public MainController(SdrService sdr, SdrProperties props) {
        this.sdr = sdr;
        this.props = props;
    }

    @EventListener
    public void onStageReady(StageReadyEvent event) {
        this.stage = event.getStage();
        this.hostServices = event.getHostServices();

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
        root.setTop(new VBox(buildMenuBar(), buildToolbar()));
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

    private MenuBar buildMenuBar() {
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> {
            sdr.stop();
            Platform.exit();
        });
        Menu file = new Menu("File");
        file.getItems().add(exit);

        MenuItem settings = new MenuItem("Settings\u2026");
        settings.setOnAction(e -> showSettingsDialog());
        Menu edit = new Menu("Edit");
        edit.getItems().add(settings);

        MenuItem about = new MenuItem("About\u2026");
        about.setOnAction(e -> showAboutDialog());
        Menu help = new Menu("Help");
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, edit, help);
        return bar;
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

        modeBox = new ComboBox<>();
        modeBox.getItems().addAll(Demodulator.Mode.values());
        modeBox.getSelectionModel().select(sdr.getDemodMode());
        modeBox.setOnAction(e -> {
            Demodulator.Mode m = modeBox.getValue();
            if (m != null) {
                sdr.setDemodMode(m);
            }
        });

        audioBtn = new ToggleButton("Audio");
        audioBtn.setOnAction(e -> toggleAudio());

        volumeSlider = new Slider(0, 1, props.getVolume());
        volumeSlider.setPrefWidth(90);
        volumeSlider.valueProperty().addListener((o, a, b) -> sdr.setVolume(b.doubleValue()));

        connectBtn.setOnAction(e -> toggleConnect());

        FlowPane bar = new FlowPane(8, 8,
                labeled("Source", sourceBox),
                labeled("Host", hostField),
                labeled("Port", portField),
                labeled("Freq (MHz)", freqField), bottomAlign(tune),
                labeled("Rate (sps)", rateBox),
                bottomAlign(autoGain),
                labeled("Gain (dB)", gainSlider),
                labeled("Mode", modeBox),
                bottomAlign(audioBtn),
                labeled("Vol", volumeSlider),
                bottomAlign(connectBtn));
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.BOTTOM_LEFT);
        bar.setRowValignment(VPos.BOTTOM);
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

    private void toggleAudio() {
        try {
            sdr.setAudioEnabled(audioBtn.isSelected());
            if (audioBtn.isSelected()) {
                statusLabel.setText("Audio on \u2014 " + sdr.getDemodMode()
                        + ". Tune to a station and Connect to hear it.");
            } else {
                statusLabel.setText("Audio off.");
            }
        } catch (Exception ex) {
            audioBtn.setSelected(false);
            statusLabel.setText("Audio error: " + ex.getMessage());
        }
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

    // ---- Help > About ----

    private void showAboutDialog() {
        Stage dialog = newModalStage("About rtlsdr-fx");

        Label name = new Label("rtlsdr-fx");
        name.setFont(Font.font("System", FontWeight.BOLD, 22));
        name.setTextFill(Color.web("#e6ecf5"));

        Label tagline = new Label("Lightweight RTL-SDR Receiver \u00b7 Spring Boot + JavaFX");
        tagline.setTextFill(Color.web("#9aa3b5"));

        Label version = new Label("Version 1.0.0");
        version.setTextFill(Color.web("#7f8aa0"));

        Label copyright = new Label("Copyright \u00a9 2026 Tauasa Timoteo");
        copyright.setTextFill(Color.web("#c7cedb"));

        Label license = new Label("Released under the MIT License.");
        license.setTextFill(Color.web("#7f8aa0"));

        String repo = "https://github.com/tauasa/rtlsdr-fx";
        Hyperlink link = new Hyperlink(repo);
        link.setOnAction(e -> {
            if (hostServices != null) {
                hostServices.showDocument(repo);
            }
        });

        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8, name, tagline, version,
                spacer(6), copyright, license, link, spacer(6), buttons);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #0b0e14;");

        dialog.setScene(new Scene(content, 420, 270));
        dialog.showAndWait();
    }

    // ---- Edit > Settings ----

    private void showSettingsDialog() {
        Stage dialog = newModalStage("Settings");

        Label header = new Label("Display Colours");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setTextFill(Color.web("#e6ecf5"));

        // Spectrum colours
        ColorPicker trace = new ColorPicker(spectrumView.getTraceColor());
        trace.valueProperty().addListener((o, a, b) -> spectrumView.setTraceColor(b));

        ColorPicker peak = new ColorPicker(spectrumView.getPeakColor());
        peak.valueProperty().addListener((o, a, b) -> spectrumView.setPeakColor(b));

        // Waterfall palette + live preview
        ComboBox<Palette> palette = new ComboBox<>();
        palette.getItems().addAll(Palette.all());
        palette.getSelectionModel().select(waterfallView.getPalette());
        Canvas preview = new Canvas(220, 16);
        drawPalettePreview(preview, waterfallView.getPalette());
        palette.setOnAction(e -> {
            Palette p = palette.getValue();
            if (p != null) {
                waterfallView.setPalette(p);
                drawPalettePreview(preview, p);
            }
        });

        VBox waterfallBox = new VBox(4, palette, preview);

        Button reset = new Button("Reset to defaults");
        reset.setOnAction(e -> {
            trace.setValue(Color.rgb(80, 200, 255));
            peak.setValue(Color.rgb(95, 105, 125));
            palette.getSelectionModel().select(Palette.CLASSIC);
            waterfallView.setPalette(Palette.CLASSIC);
            drawPalettePreview(preview, Palette.CLASSIC);
        });
        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(8, reset, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10,
                header,
                settingRow("Spectrum trace", trace),
                settingRow("Spectrum peak hold", peak),
                settingRow("Waterfall palette", waterfallBox),
                spacer(4), buttons);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #0b0e14;");

        dialog.setScene(new Scene(content, 420, 300));
        dialog.showAndWait();
    }

    private HBox settingRow(String label, Node control) {
        Label l = new Label(label);
        l.setTextFill(Color.web("#c7cedb"));
        l.setPrefWidth(150);
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void drawPalettePreview(Canvas c, Palette p) {
        GraphicsContext g = c.getGraphicsContext2D();
        double w = c.getWidth();
        double h = c.getHeight();
        for (int x = 0; x < w; x++) {
            int argb = p.color(x / (w - 1));
            int r = (argb >> 16) & 0xFF;
            int gg = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            g.setFill(Color.rgb(r, gg, b));
            g.fillRect(x, 0, 1, h);
        }
    }

    private Stage newModalStage(String title) {
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(title);
        dialog.setResizable(false);
        return dialog;
    }

    private Region spacer(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        return r;
    }
}
