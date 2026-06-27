# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A lightweight RTL-SDR receiver GUI: real-time FFT spectrum + scrolling waterfall, audio demodulation (WFM/NFM/AM/CW), and live tuning. Connects to a real dongle via `rtl_tcp` or runs in simulated mode with no hardware.

## Commands

```bash
mvn javafx:run              # run from source (no hardware needed — starts in simulated mode)
./run.sh                    # convenience wrapper around the above
mvn clean package           # build fat jar → target/rtlsdr-fx-*.jar
mvn test                    # full test suite (JUnit 5)
mvn test -Dtest=FftTest     # single test class
```

## Architecture

**Spring Boot + JavaFX bridge** (Josh Long pattern):
- `SdrApplication.main()` → `Application.launch(JavaFxApplication)`
- `JavaFxApplication.init()` boots the Spring context (headless=false), `.start()` publishes `StageReadyEvent`
- `MainController` (@Component) listens for the event and builds the entire UI programmatically (no FXML)

**Data flow:**
```
SignalSource (RtlTcpSource | SimulatedSource | SimulatedCwSource)
  └─ raw interleaved IQ float[] blocks
SdrService (@Service, background thread)
  ├─ audio path:   IQ → Demodulator → AudioPlayer (javax.sound.sampled, 48 kHz PCM)
  └─ display path: IQ → SpectrumProcessor (Hann window → radix-2 FFT → |·|² → dB → fftshift)
                       → AtomicReference<SpectrumFrame>
MainController (FX thread)
  └─ AnimationTimer → SpectrumView.draw() + WaterfallView.pushRow()
```

**Threading model:** `SdrService` runs its DSP loop on a daemon thread; all UI mutations happen on the JavaFX Application Thread via `Platform.runLater`. The latest `SpectrumFrame` is handed off through `AtomicReference` — no blocking between DSP and render.

**Key packages:**

| Package | Contents |
|---------|----------|
| `sdr` (root) | `SdrApplication`, `JavaFxApplication`, `StageReadyEvent` |
| `.dsp` | `Fft`, `Window`, `SpectrumProcessor`, `Demodulator`, `Biquad`, FIR decimators |
| `.audio` | `AudioPlayer` |
| `.source` | `SignalSource` interface + three implementations |
| `.rtl` | `RtlCommand` opcodes, `TunerType` enums (rtl_tcp protocol) |
| `.service` | `SdrService` (orchestrator), `SpectrumFrame` (record) |
| `.ui` | `MainController`, `SpectrumView`, `WaterfallView`, `Palette` |
| `.config` | `SdrProperties` (@ConfigurationProperties prefix `sdr`) |

**Adding a signal source:** implement `SignalSource`, register it in `MainController` where the source selector is built — the switch statement in `SdrService` dispatches to it.

**Configuration** lives in `src/main/resources/application.yml` under the `sdr:` prefix. All properties bind to `SdrProperties`. Runtime controls (frequency, gain, mode, volume) update the running service directly; no restart needed.
