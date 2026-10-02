package com.winlator.winhandler;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.winlator.XServerDisplayActivity;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class WinHandler {
    private static final String TAG = "WinHandler";
    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;
    private static final short LITE_PORT = 7952;
    private static final byte LITE_READY = 0x70;
    private static final byte LITE_FOCUS = 0x73;
    private static final byte LITE_TEXT_STATUS = 0x74;
    private static final byte LITE_REQUEST_TEXT = 0x01;
    private static final String WINHANDLER_LITE_PATH = "C:\\windows\\winhandler-lite.exe";
    private static final int LITE_EXEC_RETRY_DELAY_MS = 250;
    private static final int LITE_EXEC_RETRY_MAX_ATTEMPTS = 2;
    private static final int LITE_FLAG_SUBMIT = 0x00000001;
    private static final int LITE_FLAG_DELETE_BACKWARD = 0x00000002;
    private static final int LITE_MAX_UTF16_BYTES = 4096;
    private static final int TX_PACKET_MIN = 64;
    private static final int TX_PACKET_MAX = 65507;
    private static final int RX_PACKET_MAX = 2048;
    public static final byte DINPUT_MAPPER_TYPE_STANDARD = GamepadProtocol.DINPUT_MAPPER_TYPE_STANDARD;
    public static final byte DINPUT_MAPPER_TYPE_XINPUT = GamepadProtocol.DINPUT_MAPPER_TYPE_XINPUT;
    private DatagramSocket socket;
    private final ByteBuffer sendData = ByteBuffer.allocate(TX_PACKET_MAX).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(RX_PACKET_MAX).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(sendData.array(), TX_PACKET_MIN);
    private final DatagramPacket receivePacket = new DatagramPacket(receiveData.array(), RX_PACKET_MAX);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private boolean initReceived = false;
    private boolean liteReady = false;
    private boolean running = false;
    private final AtomicInteger liteReqSeq = new AtomicInteger(1);
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private final Object processSnapshotLock = new Object();
    private final ArrayList<ProcessInfo> processSnapshot = new ArrayList<>();
    private long processSnapshotUpdatedAt = 0;
    private int processSnapshotExpectedCount = 0;
    private OnImeFocusListener onImeFocusListener;
    private OnImeStatusListener onImeStatusListener;
    private ExternalController currentController;
    private InetAddress localhost;
    private byte dinputMapperType = DINPUT_MAPPER_TYPE_XINPUT;
    private final XServerDisplayActivity activity;
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList<>();

    public WinHandler(XServerDisplayActivity activity) {
        this.activity = activity;
    }

    private boolean sendPacket(int port) {
        try {
            int size = sendData.position();
            if (size == 0) return false;
            int packetSize = Math.max(size, TX_PACKET_MIN);
            if (packetSize > sendData.capacity()) {
                Log.e(TAG, "Refusing oversized packet size=" + packetSize + " port=" + port);
                return false;
            }
            sendPacket.setData(sendData.array(), 0, packetSize);
            sendPacket.setAddress(localhost);
            sendPacket.setPort(port);
            socket.send(sendPacket);
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    public void exec(String command) {
        command = command.trim();
        if (command.isEmpty()) return;
        String[] cmdList = command.split(" ", 2);
        final String filename = cmdList[0];
        final String parameters = cmdList.length > 1 ? cmdList[1] : "";
        synchronized (actions) {
            Log.i(TAG, "queue EXEC filename=" + filename + " parameters=" + parameters +
                    " initReceived=" + initReceived + " pendingBefore=" + actions.size());
        }

        addAction(() -> {
            byte[] filenameBytes = filename.getBytes();
            byte[] parametersBytes = parameters.getBytes();
            int packetSize = 13 + filenameBytes.length + parametersBytes.length;
            if (packetSize > sendData.capacity()) {
                Log.e(TAG, "Refusing oversized EXEC packet filename=" + filename +
                        " bytes=" + packetSize);
                return;
            }

            sendData.rewind();
            sendData.put(RequestCodes.EXEC);
            sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            sendData.putInt(filenameBytes.length);
            sendData.putInt(parametersBytes.length);
            sendData.put(filenameBytes);
            sendData.put(parametersBytes);
            boolean sent = sendPacket(CLIENT_PORT);
            Log.i(TAG, "send EXEC filename=" + filename + " parameters=" + parameters +
                    " sent=" + sent + " port=" + CLIENT_PORT + " initReceived=" + initReceived);
            if (isWinHandlerLiteCommand(filename)) {
                scheduleLiteExecRetry(filename, parameters, 1);
            }
        });
    }

    private boolean isWinHandlerLiteCommand(String filename) {
        return filename != null && WINHANDLER_LITE_PATH.equalsIgnoreCase(filename);
    }

    private void scheduleLiteExecRetry(final String filename, final String parameters, final int attempt) {
        if (!isWinHandlerLiteCommand(filename) || attempt > LITE_EXEC_RETRY_MAX_ATTEMPTS) return;

        Thread retryThread = new Thread(() -> {
            try {
                Thread.sleep((long) LITE_EXEC_RETRY_DELAY_MS * attempt);
            }
            catch (InterruptedException ignored) {
                return;
            }

            if (!running || liteReady) return;

            addAction(() -> {
                if (!running || liteReady) return;

                byte[] filenameBytes = filename.getBytes();
                byte[] parametersBytes = parameters.getBytes();
                int packetSize = 13 + filenameBytes.length + parametersBytes.length;
                if (packetSize > sendData.capacity()) {
                    Log.e(TAG, "Refusing oversized EXEC retry filename=" + filename +
                            " bytes=" + packetSize);
                    return;
                }

                sendData.rewind();
                sendData.put(RequestCodes.EXEC);
                sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
                sendData.putInt(filenameBytes.length);
                sendData.putInt(parametersBytes.length);
                sendData.put(filenameBytes);
                sendData.put(parametersBytes);
                boolean sent = sendPacket(CLIENT_PORT);
                Log.i(TAG, "retry EXEC filename=" + filename + " parameters=" + parameters +
                        " sent=" + sent + " port=" + CLIENT_PORT + " attempt=" + attempt +
                        " liteReady=" + liteReady + " initReceived=" + initReceived);
                scheduleLiteExecRetry(filename, parameters, attempt + 1);
            });
        }, "WinHandlerLiteRetry-" + attempt);
        retryThread.setDaemon(true);
        retryThread.start();
    }

    public void killProcess(final String processName) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KILL_PROCESS);
            byte[] bytes = processName.getBytes();
            sendData.putInt(bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void listProcesses() {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.LIST_PROCESSES);
            sendData.putInt(0);

            if (!sendPacket(CLIENT_PORT)) {
                recordProcessInfo(0, 0, null);
                if (onGetProcessInfoListener != null) onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
        });
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(() -> {
            byte[] bytes = processName.getBytes();
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9 + bytes.length);
            sendData.putInt(0);
            sendData.putInt(affinityMask);
            sendData.put((byte)bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9);
            sendData.putInt(pid);
            sendData.putInt(affinityMask);
            sendData.put((byte)0);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.MOUSE_EVENT);
            sendData.putInt(10);
            sendData.putInt(flags);
            sendData.putShort((short)dx);
            sendData.putShort((short)dy);
            sendData.putShort((short)wheelDelta);
            sendData.put((byte)((flags & MouseEventFlags.MOVE) != 0 ? 1 : 0)); // cursor pos feedback
            sendPacket(CLIENT_PORT);
        });
    }

    public boolean keyboardEvent(byte vkey, int flags) {
        if (!initReceived) return false;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KEYBOARD_EVENT);
            sendData.put(vkey);
            sendData.putInt(flags);
            sendPacket(CLIENT_PORT);
        });
        return true;
    }

    public int nextImeRequestId() {
        int value = liteReqSeq.getAndIncrement();
        if (value > 0) return value;
        liteReqSeq.compareAndSet(value + 1, 1);
        return liteReqSeq.getAndIncrement();
    }

    public int imeCommitText(String text) {
        return imeCommitText(text, false);
    }

    public int imeCommitText(String text, boolean submit) {
        return imeRequestText(text, submit ? LITE_FLAG_SUBMIT : 0);
    }

    public int imeDeleteBackward() {
        return imeRequestText("", LITE_FLAG_DELETE_BACKWARD);
    }

    private int imeRequestText(String text, int flags) {
        if (!initReceived || !liteReady) return 0;
        if (text == null) text = "";
        if (text.isEmpty() && flags == 0) return 0;

        byte[] utf16Bytes = text.getBytes(StandardCharsets.UTF_16LE);
        final int textBytes = Math.min(utf16Bytes.length, LITE_MAX_UTF16_BYTES);
        if (textBytes == 0 && flags == 0) return 0;
        final int reqId = nextImeRequestId();
        final byte[] payload = new byte[1 + 4 + 4 + 4 + textBytes];
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                .put(LITE_REQUEST_TEXT)
                .putInt(reqId)
                .putInt(textBytes)
                .putInt(flags)
                .put(utf16Bytes, 0, textBytes);

        addAction(() -> {
            if (socket == null || localhost == null) return;
            try {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, localhost, LITE_PORT);
                socket.send(packet);
            }
            catch (IOException e) {
                Log.w(TAG, "Failed to send IME payload to winhandler-lite", e);
            }
        });
        return reqId;
    }

    public boolean isReady() {
        return initReceived;
    }

    public JSONObject requestProcessSnapshot(long timeoutMs) {
        long before;
        synchronized (processSnapshotLock) {
            before = processSnapshotUpdatedAt;
        }

        if (!initReceived) return buildProcessSnapshotJson(false, true);
        listProcesses();

        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (processSnapshotLock) {
            while (running && processSnapshotUpdatedAt <= before && System.currentTimeMillis() < deadline) {
                try {
                    processSnapshotLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                }
                catch (InterruptedException ignored) {}
            }
        }
        return buildProcessSnapshotJson(initReceived, processSnapshotUpdatedAt <= before);
    }

    public JSONObject getLastProcessSnapshot() {
        return buildProcessSnapshotJson(initReceived, false);
    }

    public void bringToFront(final String processName) {
        bringToFront(processName, 0);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.BRING_TO_FRONT);
            byte[] bytes = processName.getBytes();
            sendData.putInt(bytes.length);
            sendData.put(bytes);
            sendData.putLong(handle);
            sendPacket(CLIENT_PORT);
        });
    }

    private void addAction(Runnable action) {
        synchronized (actions) {
            actions.add(action);
            actions.notify();
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    public void setOnImeFocusListener(OnImeFocusListener listener) {
        synchronized (actions) {
            this.onImeFocusListener = listener;
        }
    }

    public interface OnImeStatusListener {
        void onImeStatus(JSONObject status);
    }

    public void setOnImeStatusListener(OnImeStatusListener listener) {
        synchronized (actions) {
            this.onImeStatusListener = listener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (running) {
                synchronized (actions) {
                    while (initReceived && !actions.isEmpty()) {
                        Log.i(TAG, "drain queued action pendingBefore=" + actions.size());
                        actions.poll().run();
                    }
                    try {
                        actions.wait();
                    }
                    catch (InterruptedException e) {}
                }
            }
        });
    }

    public void stop() {
        running = false;

        if (socket != null) {
            socket.close();
            socket = null;
        }

        synchronized (actions) {
            actions.notify();
        }
    }

    private void handleRequest(byte requestCode, final int port, final int packetLen) {
        switch (requestCode) {
            case RequestCodes.INIT: {
                initReceived = true;
                Log.i(TAG, "received INIT from port=" + port + " packetLen=" + packetLen +
                        " pendingActions=" + actions.size());
                synchronized (actions) {
                    actions.notify();
                }
                break;
            }
            case RequestCodes.GET_PROCESS: {
                receiveData.position(receiveData.position() + 4);
                int numProcesses = receiveData.getShort();
                int index = receiveData.getShort();
                int pid = receiveData.getInt();
                long memoryUsage = receiveData.getLong();
                int affinityMask = receiveData.getInt();
                boolean wow64Process = receiveData.get() == 1;

                byte[] bytes = new byte[32];
                receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);

                ProcessInfo processInfo = new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64Process);

                recordProcessInfo(index, numProcesses, processInfo);
                if (onGetProcessInfoListener != null) {
                    onGetProcessInfoListener.onGetProcessInfo(index, numProcesses, processInfo);
                }
                break;
            }
            case RequestCodes.GET_GAMEPAD: {
                boolean isXInput = receiveData.get() == 1;
                boolean notify = receiveData.get() == 1;
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

                if (!useVirtualGamepad && (currentController == null || !currentController.isConnected())) {
                    currentController = ExternalController.getController(0);
                }

                final boolean enabled = currentController != null || useVirtualGamepad;

                if (enabled && notify) {
                    if (!gamepadClients.contains(port)) gamepadClients.add(port);
                }
                else gamepadClients.remove(Integer.valueOf(port));

                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD);

                    if (enabled) {
                        sendData.putInt(!useVirtualGamepad ? currentController.getDeviceId() : profile.id);
                        sendData.put(GamepadProtocol.encodeGamepadType(isXInput, dinputMapperType));
                        byte[] bytes = (useVirtualGamepad ? profile.getName() : currentController.getName()).getBytes();
                        sendData.putInt(bytes.length);
                        sendData.put(bytes);
                    }
                    else sendData.putInt(0);

                    sendPacket(port);
                });
                break;
            }
            case RequestCodes.GET_GAMEPAD_STATE: {
                int gamepadId = receiveData.getInt();
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
                final boolean enabled = currentController != null || useVirtualGamepad;

                if (currentController != null && currentController.getDeviceId() != gamepadId) currentController = null;

                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    sendData.put((byte)(enabled ? 1 : 0));

                    if (enabled) {
                        sendData.putInt(gamepadId);
                        if (useVirtualGamepad) {
                            profile.getGamepadState().writeTo(sendData);
                        }
                        else currentController.state.writeTo(sendData);
                    }

                    sendPacket(port);
                });
                break;
            }
            case RequestCodes.RELEASE_GAMEPAD: {
                currentController = null;
                gamepadClients.clear();
                break;
            }
            case RequestCodes.CURSOR_POS_FEEDBACK: {
                short x = receiveData.getShort();
                short y = receiveData.getShort();
                XServer xServer = activity.getXServer();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                activity.getXServerView().requestRender();
                break;
            }
            case LITE_READY: {
                liteReady = true;
                Log.i(TAG, "received LITE_READY packetLen=" + packetLen);
                break;
            }
            case LITE_FOCUS: {
                if (packetLen < 4) {
                    Log.w(TAG, "winhandler-lite focus packet too short len=" + packetLen);
                    break;
                }
                boolean focused = receiveData.get() != 0;
                int textLen = receiveData.getShort() & 0xffff;
                int remain = Math.max(0, packetLen - 4);
                int n = Math.min(textLen, remain);
                byte[] bytes = new byte[n];
                receiveData.get(bytes);
                String boxName = new String(bytes, StandardCharsets.UTF_8);
                if (onImeFocusListener != null) {
                    onImeFocusListener.onImeFocusChanged(focused, boxName);
                }
                break;
            }
            case LITE_TEXT_STATUS: {
                if (packetLen < 40) {
                    Log.w(TAG, "winhandler-lite status packet too short len=" + packetLen);
                    break;
                }
                int version = receiveData.get() & 0xff;
                int size = receiveData.getShort() & 0xffff;
                if (version != 1 || size < 40 || size > packetLen) {
                    Log.w(TAG, "winhandler-lite status packet invalid version=" + version +
                            " size=" + size + " len=" + packetLen);
                    break;
                }
                JSONObject status = new JSONObject();
                try {
                    int requestId = receiveData.getInt();
                    int stage = receiveData.getShort() & 0xffff;
                    int route = receiveData.getShort() & 0xffff;
                    status.put("source", "winhandler-lite");
                    status.put("event", "text-status");
                    status.put("requestId", requestId);
                    status.put("stage", stage);
                    status.put("stageName", imeStageName(stage));
                    status.put("route", route);
                    status.put("routeName", imeRouteName(route));
                    status.put("winError", receiveData.getInt());
                    status.put("hwnd", Long.toUnsignedString(receiveData.getLong()));
                    status.put("pid", Integer.toUnsignedLong(receiveData.getInt()));
                    status.put("tid", Integer.toUnsignedLong(receiveData.getInt()));
                    status.put("utf16Length", Integer.toUnsignedLong(receiveData.getInt()));
                    status.put("aux", receiveData.getInt());
                    status.put("receivedAtMs", System.currentTimeMillis());
                }
                catch (JSONException ignored) {}
                if (onImeStatusListener != null) onImeStatusListener.onImeStatus(status);
                break;
            }
            case UrlBridgeProtocol.LITE_OPEN_URL: {
                String url = UrlBridgeProtocol.parseOpenUrlPacket(receiveData.array(), packetLen);
                if (url == null) {
                    Log.w(TAG, "Rejected invalid Android browser URL packet len=" + packetLen);
                    break;
                }
                if (!activity.isAndroidBrowserUrlBridgeEnabled()) {
                    Log.w(TAG, "Rejected Android browser URL packet while bridge is disabled");
                    break;
                }
                Log.i(TAG, "Forwarding validated HTTP(S) URL to Android browser");
                activity.runOnUiThread(() -> activity.openUrlInAndroidBrowser(url));
                break;
            }
            default: {
                break;
            }
        }
    }

    public void start() {
        initReceived = false;
        liteReady = false;
        try {
            localhost = InetAddress.getLocalHost();
        }
        catch (UnknownHostException e) {
            try {
                localhost = InetAddress.getByName("127.0.0.1");
            }
            catch (UnknownHostException ex) {}
        }

        running = true;
        Log.i(TAG, "start server requested localHost=" + localhost + " serverPort=" + SERVER_PORT);
        startSendThread();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress((InetAddress)null, SERVER_PORT));
                Log.i(TAG, "UDP server bound serverPort=" + SERVER_PORT + " local=" + socket.getLocalSocketAddress());

                while (running) {
                    receivePacket.setLength(receiveData.array().length);
                    socket.receive(receivePacket);
                    int packetLen = receivePacket.getLength();

                    synchronized (actions) {
                        receiveData.rewind();
                        receiveData.limit(packetLen);
                        byte requestCode = receiveData.get();
                        handleRequest(requestCode, receivePacket.getPort(), packetLen);
                    }
                }
            }
            catch (IOException e) {
                Log.w(TAG, "UDP server failed", e);
            }
        });
    }

    public void sendGamepadState() {
        if (!initReceived || gamepadClients.isEmpty()) return;
        final ControlsProfile profile = activity.getInputControlsView().getProfile();
        final boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
        final boolean enabled = currentController != null || useVirtualGamepad;

        for (final int port : gamepadClients) {
            addAction(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                sendData.put((byte)(enabled ? 1 : 0));

                if (enabled) {
                    sendData.putInt(!useVirtualGamepad ? currentController.getDeviceId() : profile.id);
                    if (useVirtualGamepad) {
                        profile.getGamepadState().writeTo(sendData);
                    }
                    else currentController.state.writeTo(sendData);
                }

                sendPacket(port);
            });
        }
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        if (currentController != null && currentController.getDeviceId() == event.getDeviceId()) {
            handled = currentController.updateStateFromMotionEvent(event);
            if (handled) sendGamepadState();
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        boolean handled = false;
        if (currentController != null && currentController.getDeviceId() == event.getDeviceId() && event.getRepeatCount() == 0) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                handled = currentController.updateStateFromKeyEvent(event);
            }
            else if (action == KeyEvent.ACTION_UP) {
                handled = currentController.updateStateFromKeyEvent(event);
            }

            if (handled) sendGamepadState();
        }
        return handled;
    }

    public byte getDInputMapperType() {
        return dinputMapperType;
    }

    public void setDInputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }

    public ExternalController getCurrentController() {
        return currentController;
    }

    private static String imeRouteName(int route) {
        switch (route) {
            case 1: return "bridge-38442";
            case 2: return "wm-char";
            case 3: return "sendinput-unicode";
            case 4: return "clipboard-ctrl-v";
            case 5: return "key";
            default: return "none";
        }
    }

    private static String imeStageName(int stage) {
        switch (stage) {
            case 1: return "recv-text";
            case 4: return "done";
            case 5: return "invalid-packet";
            case 20: return "target-resolve";
            case 21: return "inject-begin";
            case 22: return "inject-end";
            case 23: return "inject-failed";
            case 24: return "inject-mode";
            case 44: return "attach-focus-ok";
            case 45: return "attach-focus-error";
            case 46: return "foreground-hwnd";
            case 47: return "focus-hwnd";
            case 50: return "clipboard-open-begin";
            case 51: return "clipboard-open-ok";
            case 52: return "clipboard-open-failed";
            case 53: return "clipboard-set-ok";
            case 54: return "clipboard-set-failed";
            case 55: return "ctrl-v-begin";
            case 56: return "ctrl-v-ok";
            case 57: return "ctrl-v-failed";
            case 58: return "clipboard-restore-ok";
            case 59: return "clipboard-restore-failed";
            case 60: return "target-parent";
            case 64: return "clipboard-restore-delay";
            case 65: return "bridge-sent-unacked";
            case 66: return "bridge-ack";
            default: return "stage-" + stage;
        }
    }

    private void recordProcessInfo(int index, int count, ProcessInfo processInfo) {
        synchronized (processSnapshotLock) {
            if (count <= 0) {
                processSnapshot.clear();
                processSnapshotExpectedCount = 0;
                processSnapshotUpdatedAt = System.currentTimeMillis();
                processSnapshotLock.notifyAll();
                return;
            }

            if (index == 0) {
                processSnapshot.clear();
                processSnapshotExpectedCount = count;
            }
            if (processInfo != null) processSnapshot.add(processInfo);
            if (index >= count - 1 || processSnapshot.size() >= count) {
                processSnapshotUpdatedAt = System.currentTimeMillis();
                processSnapshotLock.notifyAll();
            }
        }
    }

    private JSONObject buildProcessSnapshotJson(boolean winHandlerReady, boolean stale) {
        JSONObject json = new JSONObject();
        try {
            JSONArray processes = new JSONArray();
            synchronized (processSnapshotLock) {
                for (ProcessInfo processInfo : processSnapshot) {
                    JSONObject item = new JSONObject();
                    item.put("pid", processInfo.pid);
                    item.put("name", processInfo.name);
                    item.put("memoryUsage", processInfo.memoryUsage);
                    item.put("affinityMask", processInfo.affinityMask);
                    item.put("wow64Process", processInfo.wow64Process);
                    processes.put(item);
                }
                json.put("winHandlerReady", winHandlerReady);
                json.put("stale", stale);
                json.put("updatedAt", processSnapshotUpdatedAt);
                json.put("expectedCount", processSnapshotExpectedCount);
                json.put("processes", processes);
            }
        }
        catch (JSONException ignored) {}
        return json;
    }
}
