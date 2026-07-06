package com.example.qrbinrunner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    /*
     * =====================================================================
     * SET THIS MANUALLY.
     *
     * This app runs the binary at BINARY_PATH and passes the scanned QR text
     * or manual fallback text as EXACTLY ONE command-line argument.
     *
     * Effective argv layout:
     *     argv[0] = BINARY_PATH
     *     argv[1] = scanned-or-manually-entered-text
     *
     * Example command shape:
     *     /system/bin/your_binary_here "payload from QR or text box"
     *
     * This intentionally uses ProcessBuilder with a List<String>, not
     * "sh -c", so QR contents are passed as data, not shell syntax. Do not
     * replace this with Runtime.exec("some string " + payload) unless you
     * enjoy inventing command injection bugs like a raccoon with a keyboard.
     * =====================================================================
     */
    private static final String BINARY_PATH = "/system/bin/REPLACE_ME";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText manualInput;
    private Button scanButton;
    private Button runManualButton;
    private ProgressBar progressBar;
    private TextView statusView;
    private TextView outputView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("QR Bin Runner");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scanButton = new Button(this);
        scanButton.setText("Scan QR code");
        scanButton.setOnClickListener(v -> startQrScan());
        root.addView(scanButton, matchWrap());

        manualInput = new EditText(this);
        manualInput.setHint("Manual fallback input");
        manualInput.setSingleLine(false);
        manualInput.setMinLines(3);
        manualInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(manualInput, matchWrap());

        runManualButton = new Button(this);
        runManualButton.setText("Run manual input");
        runManualButton.setOnClickListener(v -> {
            String payload = manualInput.getText().toString();
            if (payload.isEmpty()) {
                Toast.makeText(this, "Manual input is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            runBinary(payload);
        });
        root.addView(runManualButton, matchWrap());

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(dp(64), dp(64)));

        statusView = new TextView(this);
        statusView.setText("Idle. Scan something, or type it manually like a civilized fallback mechanism.");
        statusView.setTextSize(14);
        root.addView(statusView, matchWrap());

        outputView = new TextView(this);
        outputView.setTextIsSelectable(true);
        outputView.setText("Output will appear here.");
        outputView.setTextSize(14);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(outputView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        setContentView(root);
    }

    private void startQrScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan QR code");
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents == null) {
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
            } else {
                manualInput.setText(contents);
                runBinary(contents);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void runBinary(String payload) {
        setBusy(true);
        statusView.setText("Running binary...");
        outputView.setText("Waiting for process to exit. The wheel spins, because apparently that comforts mammals.");

        executor.execute(() -> {
            RunResult result = runBinaryBlocking(payload);
            mainHandler.post(() -> {
                setBusy(false);
                statusView.setText("Process finished. Exit code: " + result.exitCode);

                StringBuilder display = new StringBuilder();
                display.append("Command:\n");
                display.append(BINARY_PATH).append(" <payload-as-one-argv>\n\n");
                display.append("Exit code:\n").append(result.exitCode).append("\n\n");
                display.append("Stdout:\n");
                display.append(result.stdout.isEmpty() ? "<empty>\n" : result.stdout);

                // Not requested, but captured so stderr cannot block the child process.
                if (!result.stderr.isEmpty()) {
                    display.append("\nStderr:\n").append(result.stderr);
                }

                if (result.exceptionText != null) {
                    display.append("\nException:\n").append(result.exceptionText).append('\n');
                }

                outputView.setText(display.toString());
            });
        });
    }

    private RunResult runBinaryBlocking(String payload) {
        List<String> command = new ArrayList<>();
        command.add(BINARY_PATH);
        command.add(payload);

        Process process = null;
        StreamSlurper stdoutReader = null;
        StreamSlurper stderrReader = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            // Keep stdout and stderr separate. We still read both to avoid deadlocks
            // if the child writes a lot to stderr while this app waits.
            pb.redirectErrorStream(false);

            process = pb.start();

            // We do not write to stdin. Close it so a child waiting for EOF does not hang.
            process.getOutputStream().close();

            stdoutReader = new StreamSlurper(process.getInputStream());
            stderrReader = new StreamSlurper(process.getErrorStream());
            stdoutReader.start();
            stderrReader.start();

            int exitCode = process.waitFor();
            stdoutReader.join();
            stderrReader.join();

            return new RunResult(exitCode, stdoutReader.getText(), stderrReader.getText(), null);
        } catch (IOException e) {
            return new RunResult(-1,
                    stdoutReader == null ? "" : stdoutReader.getText(),
                    stderrReader == null ? "" : stderrReader.getText(),
                    "IOException: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroy();
            }
            return new RunResult(-1,
                    stdoutReader == null ? "" : stdoutReader.getText(),
                    stderrReader == null ? "" : stderrReader.getText(),
                    "InterruptedException: " + e.getMessage());
        }
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        scanButton.setEnabled(!busy);
        runManualButton.setEnabled(!busy);
        manualInput.setEnabled(!busy);
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class RunResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        final String exceptionText;

        RunResult(int exitCode, String stdout, String stderr, String exceptionText) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exceptionText = exceptionText;
        }
    }

    private static final class StreamSlurper extends Thread {
        private final InputStream inputStream;
        private final StringBuilder text = new StringBuilder();

        StreamSlurper(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            } catch (IOException e) {
                text.append("[stream read failed: ").append(e.getMessage()).append("]\n");
            }
        }

        String getText() {
            return text.toString();
        }
    }
}
