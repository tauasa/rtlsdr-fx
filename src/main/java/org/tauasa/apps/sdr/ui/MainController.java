package org.tauasa.apps.sdr.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.tauasa.apps.sdr.StageReadyEvent;
import org.tauasa.apps.sdr.config.SdrProperties;
import org.tauasa.apps.sdr.dsp.Demodulator;
import org.tauasa.apps.sdr.scan.Scanner;
import org.tauasa.apps.sdr.service.SdrService;
import org.tauasa.apps.sdr.service.SpectrumFrame;

import javafx.animation.AnimationTimer;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
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
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Builds and drives the JavaFX UI. A Spring bean that reacts to the stage event. */
@Component
public final class MainController {

    private final SdrService sdr;
    private final SdrProperties props;
    private final Scanner scanner;
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
    private ComboBox<String> hostCombo;
    private TextField portField;
    private ComboBox<String> freqCombo;
    private ComboBox<Integer> rateBox;
    private CheckBox autoGain;
    private Slider gainSlider;
    private ComboBox<Demodulator.Mode> modeBox;
    private ToggleButton audioBtn;
    private Slider volumeSlider;
    private ToggleButton scanBtn;

    private BorderPane root;
    private Node menuRow;
    private boolean toolbarVisible = true;
    private boolean toolbarHorizontal = true;

    public MainController(SdrService sdr, SdrProperties props, Scanner scanner) {
        this.sdr = sdr;
        this.props = props;
        this.scanner = scanner;
    }

    @EventListener
    public void onStageReady(StageReadyEvent event) {
        this.stage = event.getStage();
        this.hostServices = event.getHostServices();

        spectrumView = new SpectrumView(props.getMinDb(), props.getMaxDb());
        spectrumView.setOnFrequencyChanged(freq -> {
            stopScanIfActive();
            sdr.setCenterFrequency(freq);
            freqCombo.getEditor().setText(String.format("%.4f", freq / 1e6));
        });
        spectrumView.setOnSpectrumClicked(this::toggleScanPause);
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

        initToolbarControls();

        root = new BorderPane();
        root.setStyle("-fx-background-color: #0b0e14;");
        menuRow = buildMenuRow();
        root.setCenter(split);
        root.setBottom(buildStatusBar());

        applyToolbarLayout();

        sdr.setFrameSink(latest::set);

        scanner.setOnTune(freq -> Platform.runLater(() -> {
            freqCombo.getEditor().setText(String.format("%.4f", freq / 1e6));
            statusLabel.setText(String.format("Scanning… %.4f MHz", freq / 1e6));
        }));
        scanner.setOnDetect(freq -> Platform.runLater(() ->
                statusLabel.setText(String.format("Signal detected at %.4f MHz — listening…", freq / 1e6))));
        scanner.setOnStopped(() -> Platform.runLater(() -> {
            scanBtn.setSelected(false);
            updateScanControls(false);
        }));

        Scene scene = new Scene(root, 1400, 760);
        stage.setScene(scene);
        stage.setTitle("rtlsdr-fx — Lightweight RTL-SDR Receiver");
        stage.setOnCloseRequest(e -> {
            scanner.stop();
            sdr.stop();
        });
        stage.show();

        startRenderLoop();

        connectBtn.setSelected(true);
        toggleConnect();
    }

    private void initToolbarControls() {
        sourceBox = new ComboBox<>();
        sourceBox.getItems().addAll("RTL-TCP", "Simulated", "Simulated (CW)");
        sourceBox.getSelectionModel().select("Simulated");
        sourceBox.setOnAction(e -> {
            if ("Simulated (CW)".equals(sourceBox.getValue()) && modeBox != null) {
                modeBox.getSelectionModel().select(Demodulator.Mode.CW);
                sdr.setDemodMode(Demodulator.Mode.CW);
            }
        });

        hostCombo = new ComboBox<>();
        hostCombo.setEditable(true);
        hostCombo.setPrefWidth(110);
        hostCombo.getEditor().setText(props.getHost());
        loadSavedHosts();

        portField = new TextField(Integer.toString(props.getPort()));
        portField.setPrefWidth(60);

        freqCombo = new ComboBox<>();
        freqCombo.setEditable(true);
        freqCombo.setPrefWidth(130);
        freqCombo.getEditor().setText(String.format("%.4f", props.getCenterFrequency() / 1e6));
        freqCombo.getEditor().setOnAction(e -> applyFrequency());
        freqCombo.setOnAction(e -> {
            if (freqCombo.getValue() != null) applyFrequency();
        });
        loadSavedFrequencies();

        scanBtn = new ToggleButton("Scan");
        scanBtn.setOnAction(e -> toggleScan());

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
        updateAudioControls(false);

        connectBtn.setOnAction(e -> toggleConnect());

        applyLastConnection();
    }

    private Node buildMenuRow() {
        Button hamburger = new Button("☰");
        hamburger.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #c7cedb; -fx-font-size: 14; -fx-padding: 2 10 2 10; -fx-cursor: hand;");
        hamburger.setOnAction(e -> setToolbarVisible(!toolbarVisible));

        MenuBar bar = buildMenuBar();
        HBox.setHgrow(bar, Priority.ALWAYS);

        HBox row = new HBox(hamburger, bar);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color: #11151f;");
        return row;
    }

    private MenuBar buildMenuBar() {
        MenuItem saveFreq = new MenuItem("Save Current Freq");
        saveFreq.setOnAction(e -> saveCurrentFrequency());
        MenuItem saveHost = new MenuItem("Save Current Host");
        saveHost.setOnAction(e -> saveCurrentHost());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> {
            sdr.stop();
            Platform.exit();
        });
        Menu file = new Menu("File");
        file.getItems().addAll(saveFreq, saveHost, new SeparatorMenuItem(), exit);

        MenuItem frequencies = new MenuItem("Frequencies…");
        frequencies.setOnAction(e -> showFrequenciesDialog());
        MenuItem hosts = new MenuItem("Hosts…");
        hosts.setOnAction(e -> showHostsDialog());
        MenuItem settings = new MenuItem("Settings…");
        settings.setOnAction(e -> showSettingsDialog());
        MenuItem scan = new MenuItem("Scan…");
        scan.setOnAction(e -> showScanDialog());
        Menu edit = new Menu("Edit");
        edit.getItems().addAll(frequencies, hosts, new SeparatorMenuItem(), settings, new SeparatorMenuItem(), scan);

        RadioMenuItem tbHorizontal = new RadioMenuItem("Horizontal");
        RadioMenuItem tbVertical = new RadioMenuItem("Vertical");
        ToggleGroup tg = new ToggleGroup();
        tbHorizontal.setToggleGroup(tg);
        tbVertical.setToggleGroup(tg);
        tbHorizontal.setSelected(true);
        tbHorizontal.setOnAction(e -> setToolbarOrientation(true));
        tbVertical.setOnAction(e -> setToolbarOrientation(false));
        Menu toolbarMenu = new Menu("Toolbar");
        toolbarMenu.getItems().addAll(tbHorizontal, tbVertical);
        Menu view = new Menu("View");
        view.getItems().add(toolbarMenu);

        MenuItem about = new MenuItem("About…");
        about.setOnAction(e -> showAboutDialog());
        Menu help = new Menu("Help");
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, edit, view, help);
        return bar;
    }

    private void setToolbarVisible(boolean visible) {
        toolbarVisible = visible;
        applyToolbarLayout();
    }

    private void setToolbarOrientation(boolean horizontal) {
        toolbarHorizontal = horizontal;
        applyToolbarLayout();
    }

    private void applyToolbarLayout() {
        if (!toolbarVisible) {
            root.setTop(menuRow);
            root.setLeft(null);
        } else if (toolbarHorizontal) {
            root.setTop(new VBox(menuRow, buildHorizontalToolbar()));
            root.setLeft(null);
        } else {
            root.setTop(menuRow);
            root.setLeft(buildVerticalToolbar());
        }
    }

    private Node buildHorizontalToolbar() {
        gainSlider.setPrefWidth(140);
        volumeSlider.setPrefWidth(90);
        freqCombo.setPrefWidth(130);
        hostCombo.setPrefWidth(110);

        HBox bar = new HBox(8,
                labeled("Source", sourceBox),
                labeled("Host", hostCombo),
                labeled("Port", portField),
                bottomAlign(connectBtn),
                toolbarDividerVertical(),
                labeledBold("Freq (MHz)", freqCombo), bottomAlign(scanBtn),
                toolbarDividerVertical(),
                labeled("Rate (sps)", rateBox),
                bottomAlign(autoGain),
                labeled("Gain (dB)", gainSlider),
                toolbarDividerVertical(),
                labeled("Mode", modeBox),
                toolbarDividerVertical(),
                labeled("Vol", volumeSlider),
                bottomAlign(audioBtn));
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.BOTTOM_LEFT);
        bar.setStyle("-fx-background-color: #11151f;");

        // A horizontal scrollbar (rather than wrapping to a second row) keeps every
        // control on one level regardless of window width.
        ScrollPane scroll = new ScrollPane(bar);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: #11151f; -fx-background: #11151f;"
                + " -fx-border-color: #1d2230; -fx-border-width: 0 0 1 0;");
        return scroll;
    }

    private Node buildVerticalToolbar() {
        gainSlider.setPrefWidth(130);
        volumeSlider.setPrefWidth(130);
        freqCombo.setPrefWidth(120);
        hostCombo.setPrefWidth(120);

        VBox bar = new VBox(6,
                labeled("Source", sourceBox),
                labeled("Host", hostCombo),
                labeled("Port", portField),
                bottomAlign(connectBtn),
                toolbarDivider(),
                labeledBold("Freq (MHz)", freqCombo),
                bottomAlign(scanBtn),
                toolbarDivider(),
                labeled("Rate (sps)", rateBox),
                bottomAlign(autoGain),
                labeled("Gain (dB)", gainSlider),
                toolbarDivider(),
                labeled("Mode", modeBox),
                toolbarDivider(),
                labeled("Vol", volumeSlider),
                bottomAlign(audioBtn));
        bar.setPadding(new Insets(8));
        bar.setStyle("-fx-background-color: #11151f; -fx-border-color: #1d2230; -fx-border-width: 0 1 0 0;");

        ScrollPane scroll = new ScrollPane(bar);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(160);
        scroll.setStyle("-fx-background-color: #11151f; -fx-background: #11151f;");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private VBox labeled(String text, Node node) {
        Label l = new Label(text);
        l.setTextFill(Color.WHITE);
        l.setStyle("-fx-font-size: 10;");
        return new VBox(2, l, node);
    }

    private VBox labeledBold(String text, Node node) {
        Label l = new Label(text);
        l.setTextFill(Color.WHITE);
        l.setStyle("-fx-font-size: 10; -fx-font-weight: bold;");
        return new VBox(2, l, node);
    }

    private Region toolbarDivider() {
        Region divider = new Region();
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setStyle("-fx-background-color: #5b6b91;");
        return divider;
    }

    private Region toolbarDividerVertical() {
        Region divider = new Region();
        divider.setMinWidth(1);
        divider.setPrefWidth(1);
        divider.setMaxWidth(1);
        divider.setMaxHeight(Double.MAX_VALUE);
        divider.setStyle("-fx-background-color: #5b6b91;");
        return divider;
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
            double mhz = Double.parseDouble(freqCombo.getEditor().getText().trim());
            stopScanIfActive();
            sdr.setCenterFrequency((long) (mhz * 1e6));
        } catch (NumberFormatException ignored) {
            // leave the previous frequency in place
        }
    }

    private static final Path FREQ_FILE =
            Path.of(System.getProperty("user.home"), ".rtlsdr-fx", "frequencies.txt");

    private void saveCurrentFrequency() {
        String text = freqCombo.getEditor().getText().trim();
        try {
            double mhz = Double.parseDouble(text);
            String entry = String.format("%.4f", mhz);
            Files.createDirectories(FREQ_FILE.getParent());
            List<String> lines = Files.exists(FREQ_FILE)
                    ? new ArrayList<>(Files.readAllLines(FREQ_FILE))
                    : new ArrayList<>();
            if (!lines.contains(entry)) {
                lines.add(entry);
                lines.sort(Comparator.comparingDouble(Double::parseDouble));
                Files.write(FREQ_FILE, lines);
            }
            loadSavedFrequencies();
            statusLabel.setText("Saved " + entry + " MHz.");
        } catch (NumberFormatException e) {
            statusLabel.setText("Cannot save: not a valid frequency.");
        } catch (IOException e) {
            statusLabel.setText("Cannot save frequency: " + e.getMessage());
        }
    }

    private void loadSavedFrequencies() {
        List<String> saved = new ArrayList<>();
        if (Files.exists(FREQ_FILE)) {
            try {
                saved = new ArrayList<>(Files.readAllLines(FREQ_FILE));
            } catch (IOException ignored) {}
        }
        saved.sort(Comparator.comparingDouble(Double::parseDouble));
        String current = freqCombo.getEditor().getText();
        freqCombo.getItems().setAll(saved);
        freqCombo.getEditor().setText(current);
    }

    private void removeSavedFrequency(String entry) {
        try {
            if (!Files.exists(FREQ_FILE)) {
                return;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(FREQ_FILE));
            lines.remove(entry);
            Files.write(FREQ_FILE, lines);
            loadSavedFrequencies();
        } catch (IOException e) {
            statusLabel.setText("Cannot remove frequency: " + e.getMessage());
        }
    }

    private static final Path HOST_FILE =
            Path.of(System.getProperty("user.home"), ".rtlsdr-fx", "hosts.txt");

    private void saveCurrentHost() {
        String entry = hostCombo.getEditor().getText().trim();
        if (entry.isEmpty()) {
            statusLabel.setText("Cannot save: host is empty.");
            return;
        }
        try {
            Files.createDirectories(HOST_FILE.getParent());
            List<String> lines = Files.exists(HOST_FILE)
                    ? new ArrayList<>(Files.readAllLines(HOST_FILE))
                    : new ArrayList<>();
            if (!lines.contains(entry)) {
                lines.add(entry);
                lines.sort(Comparator.naturalOrder());
                Files.write(HOST_FILE, lines);
            }
            loadSavedHosts();
            statusLabel.setText("Saved host " + entry + ".");
        } catch (IOException e) {
            statusLabel.setText("Cannot save host: " + e.getMessage());
        }
    }

    private void loadSavedHosts() {
        List<String> saved = new ArrayList<>();
        if (Files.exists(HOST_FILE)) {
            try {
                saved = new ArrayList<>(Files.readAllLines(HOST_FILE));
            } catch (IOException ignored) {}
        }
        saved.sort(Comparator.naturalOrder());
        String current = hostCombo.getEditor().getText();
        hostCombo.getItems().setAll(saved);
        hostCombo.getEditor().setText(current);
    }

    private void removeSavedHost(String entry) {
        try {
            if (!Files.exists(HOST_FILE)) {
                return;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(HOST_FILE));
            lines.remove(entry);
            Files.write(HOST_FILE, lines);
            loadSavedHosts();
        } catch (IOException e) {
            statusLabel.setText("Cannot remove host: " + e.getMessage());
        }
    }

    private static final Path LAST_CONNECTION_FILE =
            Path.of(System.getProperty("user.home"), ".rtlsdr-fx", "last-connection.properties");

    private void saveLastConnection() {
        try {
            Files.createDirectories(LAST_CONNECTION_FILE.getParent());
            Properties p = new Properties();
            p.setProperty("source", sourceBox.getValue());
            p.setProperty("host", hostCombo.getEditor().getText().trim());
            p.setProperty("port", portField.getText().trim());
            try (var out = Files.newOutputStream(LAST_CONNECTION_FILE)) {
                p.store(out, "Last used connection");
            }
        } catch (IOException ignored) {}
    }

    private void applyLastConnection() {
        Properties p = new Properties();
        if (Files.exists(LAST_CONNECTION_FILE)) {
            try (var in = Files.newInputStream(LAST_CONNECTION_FILE)) {
                p.load(in);
            } catch (IOException ignored) {}
        }
        String source = p.getProperty("source");
        if (source != null && sourceBox.getItems().contains(source)) {
            sourceBox.getSelectionModel().select(source);
        }
        String host = p.getProperty("host");
        if (host != null && !host.isEmpty()) {
            hostCombo.getEditor().setText(host);
        }
        String port = p.getProperty("port");
        if (port != null && !port.isEmpty()) {
            portField.setText(port);
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
                    sdr.startRtl(hostCombo.getEditor().getText().trim(), Integer.parseInt(portField.getText().trim()));
                } else if ("Simulated (CW)".equals(sourceBox.getValue())) {
                    sdr.startSimulatedCw();
                } else {
                    sdr.startSimulated();
                }
                if (!autoGain.isSelected()) {
                    sdr.setGain((int) Math.round(gainSlider.getValue() * 10));
                }
                connectBtn.setText("Disconnect");
                setControlsConnected(true);
                saveLastConnection();
            } catch (Exception ex) {
                connectBtn.setSelected(false);
                statusLabel.setText("Connect failed: " + ex.getMessage());
            }
        } else {
            stopScanIfActive();
            sdr.stop();
            connectBtn.setText("Connect");
            setControlsConnected(false);
            statusLabel.setText("Stopped.");
        }
    }

    private void toggleScan() {
        if (scanBtn.isSelected()) {
            if (!sdr.isRunning()) {
                scanBtn.setSelected(false);
                statusLabel.setText("Connect to a source before scanning.");
                return;
            }
            scanner.start();
            updateScanControls(true);
            statusLabel.setText(String.format("Scanning %.1f–%.1f MHz…",
                    scanner.getStartFrequency() / 1e6, scanner.getStopFrequency() / 1e6));
        } else {
            scanner.stop();
            updateScanControls(false);
            statusLabel.setText("Scan stopped.");
        }
    }

    /** Single-click anywhere on the spectrum during a scan: pause in place, click again to resume. */
    private void toggleScanPause() {
        if (!scanner.isScanning()) {
            return;
        }
        if (scanner.isPaused()) {
            scanner.resume();
            statusLabel.setText("Scan resumed.");
        } else {
            scanner.pause();
            SpectrumFrame f = latest.get();
            if (f != null) {
                freqCombo.getEditor().setText(String.format("%.4f", f.centerFrequency() / 1e6));
                statusLabel.setText(String.format("Scan paused at %.4f MHz — click spectrum to resume.",
                        f.centerFrequency() / 1e6));
            }
        }
    }

    private void updateScanControls(boolean scanningOn) {
        scanBtn.setStyle(scanningOn ? "-fx-base: #2ecfa1;" : "");
        freqCombo.setDisable(scanningOn);
        spectrumView.setScanning(scanningOn);
    }

    private void stopScanIfActive() {
        if (scanner.isScanning()) {
            scanner.stop();
            scanBtn.setSelected(false);
            updateScanControls(false);
        }
    }

    private void setControlsConnected(boolean connected) {
        sourceBox.setDisable(connected);
        hostCombo.setDisable(connected);
        portField.setDisable(connected);
        connectBtn.setStyle(connected ? "-fx-base: #2ecfa1;" : "");
    }

    private void toggleAudio() {
        try {
            sdr.setAudioEnabled(audioBtn.isSelected());
            if (audioBtn.isSelected()) {
                statusLabel.setText("Audio on — " + sdr.getDemodMode()
                        + ". Tune to a station and Connect to hear it.");
            } else {
                statusLabel.setText("Audio off.");
            }
        } catch (Exception ex) {
            audioBtn.setSelected(false);
            statusLabel.setText("Audio error: " + ex.getMessage());
        }
        updateAudioControls(audioBtn.isSelected());
    }

    private void updateAudioControls(boolean audioOn) {
        audioBtn.setStyle(audioOn ? "-fx-base: #2ecfa1;" : "");
        volumeSlider.setDisable(!audioOn);
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

        Label tagline = new Label("Lightweight RTL-SDR Receiver · Spring Boot + JavaFX");
        tagline.setTextFill(Color.web("#9aa3b5"));

        Label version = new Label("Version 1.0.5");
        version.setTextFill(Color.web("#7f8aa0"));

        Label copyright = new Label("Copyright © 2026 Tauasa Timoteo");
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

    // ---- Edit > Frequencies ----

    private void showFrequenciesDialog() {
        Stage dialog = newModalStage("Frequencies");

        Label header = new Label("Saved Frequencies (MHz)");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setTextFill(Color.web("#e6ecf5"));

        ListView<String> list = new ListView<>();
        list.getItems().setAll(freqCombo.getItems());
        list.setPrefHeight(240);

        Button remove = new Button("Remove Selected");
        remove.setDisable(true);
        remove.setOnAction(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                removeSavedFrequency(selected);
                list.getItems().setAll(freqCombo.getItems());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener(
                (o, a, b) -> remove.setDisable(b == null));

        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(8, remove, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10, header, list, buttons);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #0b0e14;");

        dialog.setScene(new Scene(content, 320, 360));
        dialog.showAndWait();
    }

    // ---- Edit > Hosts ----

    private void showHostsDialog() {
        Stage dialog = newModalStage("Hosts");

        Label header = new Label("Saved Hosts");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setTextFill(Color.web("#e6ecf5"));

        ListView<String> list = new ListView<>();
        list.getItems().setAll(hostCombo.getItems());
        list.setPrefHeight(240);

        Button remove = new Button("Remove Selected");
        remove.setDisable(true);
        remove.setOnAction(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                removeSavedHost(selected);
                list.getItems().setAll(hostCombo.getItems());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener(
                (o, a, b) -> remove.setDisable(b == null));

        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(8, remove, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10, header, list, buttons);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #0b0e14;");

        dialog.setScene(new Scene(content, 320, 360));
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

        ColorPicker marker = new ColorPicker(spectrumView.getMarkerColor());
        marker.valueProperty().addListener((o, a, b) -> spectrumView.setMarkerColor(b));

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

        // CW (Morse) controls
        Label cwHeader = new Label("CW (Morse)");
        cwHeader.setFont(Font.font("System", FontWeight.BOLD, 14));
        cwHeader.setTextFill(Color.web("#e6ecf5"));

        Slider cwPitch = new Slider(300, 1000, sdr.getCwPitch());
        cwPitch.setPrefWidth(170);
        Label cwPitchVal = new Label(Math.round(sdr.getCwPitch()) + " Hz");
        cwPitchVal.setTextFill(Color.web("#7f8aa0"));
        cwPitch.valueProperty().addListener((o, a, b) -> {
            sdr.setCwParams(b.doubleValue(), sdr.getCwBandwidth());
            cwPitchVal.setText(Math.round(b.doubleValue()) + " Hz");
        });

        Slider cwBw = new Slider(100, 1000, sdr.getCwBandwidth());
        cwBw.setPrefWidth(170);
        Label cwBwVal = new Label(Math.round(sdr.getCwBandwidth()) + " Hz");
        cwBwVal.setTextFill(Color.web("#7f8aa0"));
        cwBw.valueProperty().addListener((o, a, b) -> {
            sdr.setCwParams(sdr.getCwPitch(), b.doubleValue());
            cwBwVal.setText(Math.round(b.doubleValue()) + " Hz");
        });

        Button reset = new Button("Reset to defaults");
        reset.setOnAction(e -> {
            trace.setValue(Color.rgb(80, 200, 255));
            peak.setValue(Color.rgb(227, 243, 10));
            marker.setValue(Color.rgb(255, 200, 80));
            palette.getSelectionModel().select(Palette.CLASSIC);
            waterfallView.setPalette(Palette.CLASSIC);
            drawPalettePreview(preview, Palette.CLASSIC);
            cwPitch.setValue(700);
            cwBw.setValue(300);
        });
        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(8, reset, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10,
                header,
                settingRow("Spectrum trace", trace),
                settingRow("Spectrum peak hold", peak),
                settingRow("Frequency marker", marker),
                settingRow("Waterfall palette", waterfallBox),
                cwHeader,
                settingRow("CW pitch", new HBox(8, cwPitch, cwPitchVal)),
                settingRow("CW filter width", new HBox(8, cwBw, cwBwVal)),
                spacer(4), buttons);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #0b0e14;");

        dialog.setScene(new Scene(content, 440, 420));
        dialog.showAndWait();
    }

    // ---- Edit > Scan ----

    private void showScanDialog() {
        Stage dialog = newModalStage("Scan Settings");

        Label header = new Label("Frequency Scan");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setTextFill(Color.web("#e6ecf5"));

        TextField startField = new TextField(String.format("%.4f", scanner.getStartFrequency() / 1e6));
        TextField stopField = new TextField(String.format("%.4f", scanner.getStopFrequency() / 1e6));
        TextField stepField = new TextField(String.format("%.1f", scanner.getStepHz() / 1e3));
        TextField thresholdField = new TextField(String.format("%.1f", scanner.getThresholdDb()));
        TextField settleField = new TextField(Long.toString(scanner.getSettleMs()));
        TextField lingerField = new TextField(String.format("%.1f", scanner.getLingerMs() / 1000.0));
        for (TextField tf : new TextField[] {startField, stopField, stepField, thresholdField, settleField, lingerField}) {
            tf.setPrefWidth(110);
        }

        CheckBox loopBox = new CheckBox("Loop continuously");
        loopBox.setSelected(scanner.isLoop());
        loopBox.setTextFill(Color.web("#c7cedb"));

        Label error = new Label(" ");
        error.setTextFill(Color.web("#ff6b6b"));
        error.setWrapText(true);

        Button apply = new Button("Apply");
        apply.setOnAction(e -> {
            try {
                long start = Math.round(Double.parseDouble(startField.getText().trim()) * 1e6);
                long stop = Math.round(Double.parseDouble(stopField.getText().trim()) * 1e6);
                long step = Math.round(Double.parseDouble(stepField.getText().trim()) * 1e3);
                double threshold = Double.parseDouble(thresholdField.getText().trim());
                long settle = Long.parseLong(settleField.getText().trim());
                long linger = Math.round(Double.parseDouble(lingerField.getText().trim()) * 1000);
                if (stop <= start) {
                    error.setText("Stop frequency must be greater than start frequency.");
                    return;
                }
                if (step <= 0) {
                    error.setText("Step must be greater than zero.");
                    return;
                }
                scanner.setStartFrequency(start);
                scanner.setStopFrequency(stop);
                scanner.setStepHz(step);
                scanner.setThresholdDb(threshold);
                scanner.setSettleMs(settle);
                scanner.setLingerMs(linger);
                scanner.setLoop(loopBox.isSelected());
                error.setText(" ");
            } catch (NumberFormatException ex) {
                error.setText("Enter valid numbers in every field.");
            }
        });

        Button reset = new Button("Reset to defaults");
        reset.setOnAction(e -> {
            startField.setText(String.format("%.4f", props.getScanStartFrequency() / 1e6));
            stopField.setText(String.format("%.4f", props.getScanStopFrequency() / 1e6));
            stepField.setText(String.format("%.1f", props.getScanStepHz() / 1e3));
            thresholdField.setText(String.format("%.1f", props.getScanThresholdDb()));
            settleField.setText(Long.toString(props.getScanSettleMs()));
            lingerField.setText(String.format("%.1f", props.getScanLingerMs() / 1000.0));
            loopBox.setSelected(props.isScanLoop());
            apply.fire();
        });

        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(8, reset, apply, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10,
                header,
                settingRow("Start freq (MHz)", startField),
                settingRow("Stop freq (MHz)", stopField),
                settingRow("Step (kHz)", stepField),
                settingRow("Threshold (dB)", thresholdField),
                settingRow("Settle time (ms)", settleField),
                settingRow("Linger time (s)", lingerField),
                settingRow("", loopBox),
                error,
                spacer(4), buttons);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #0b0e14;");

        dialog.setScene(new Scene(content, 380, 460));
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
