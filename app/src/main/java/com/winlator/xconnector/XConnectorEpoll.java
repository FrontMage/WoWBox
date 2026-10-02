package com.winlator.xconnector;

import android.util.SparseArray;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
    private final ConnectionHandler connectionHandler;
    private final RequestHandler requestHandler;
    private final int epollFd;
    private final int serverFd;
    private final int shutdownFd;
    private Thread epollThread;
    private volatile boolean running = false;
    private boolean multithreadedClients = false;
    private boolean canReceiveAncillaryMessages = false;
    private int initialInputBufferCapacity = 4096;
    private int initialOutputBufferCapacity = 4096;
    private final SparseArray<Client> connectedClients = new SparseArray<>();

    static {
        System.loadLibrary("winlator");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;

        serverFd = createAFUnixSocket(socketConfig.path);
        if (serverFd < 0) {
            throw new RuntimeException("Failed to create an AF_UNIX socket.");
        }

        epollFd = createEpollFd();
        if (epollFd < 0) {
            closeFd(serverFd);
            throw new RuntimeException("Failed to create epoll fd.");
        }

        if (!addFdToEpoll(epollFd, serverFd)) {
            closeFd(serverFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }

        shutdownFd = createEventFd();
        if (!addFdToEpoll(epollFd, shutdownFd)) {
            closeFd(serverFd);
            closeFd(shutdownFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }

        epollThread = new Thread(this);
    }

    public synchronized void start() {
        if (running || epollThread == null) return;
        running = true;
        epollThread.start();
    }

    public synchronized void stop() {
        if (!running || epollThread == null) return;
        running = false;
        requestShutdown();
        epollThread.interrupt();
        epollThread = null;
    }

    @Override
    public void run() {
        while (running && doEpollIndefinitely(epollFd, serverFd, !multithreadedClients));
        shutdown();
    }

    @Keep
    private void handleNewConnection(int fd) {
        final Client client = new Client(this, new ClientSocket(fd));
        client.connected = true;

        if (multithreadedClients) {
            client.shutdownFd = createEventFd();
            if (client.shutdownFd < 0) {
                client.connected = false;
                closeFd(client.clientSocket.fd);
                return;
            }

            addClient(client);
            client.pollThread = new Thread(() -> {
                try {
                    connectionHandler.handleNewConnection(client);
                    client.connectionHandlerInitialized = true;
                    while (client.connected &&
                           waitForSocketRead(client.clientSocket.fd, client.shutdownFd));
                }
                finally {
                    killConnection(client);
                }
            }, "XConnectorClient-" + fd);

            try {
                client.pollThread.start();
            }
            catch (RuntimeException | Error e) {
                killConnection(client);
                throw e;
            }
        }
        else {
            addClient(client);
            try {
                connectionHandler.handleNewConnection(client);
                client.connectionHandlerInitialized = true;
            }
            catch (RuntimeException | Error e) {
                killConnection(client);
                throw e;
            }
        }
    }

    @Keep
    private void handleExistingConnection(int fd) {
        Client client = getClient(fd);
        if (client == null || !client.connected) return;

        XInputStream inputStream = client.getInputStream();
        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (running && client.connected && requestHandler.handleRequest(client)) {
                        activePosition = inputStream.getActivePosition();
                    }
                    inputStream.setActivePosition(activePosition);
                }
                else killConnection(client);
            }
            else requestHandler.handleRequest(client);
        }
        catch (IOException e) {
            killConnection(client);
        }
    }

    public Client getClient(int fd) {
        synchronized (connectedClients) {
            return connectedClients.get(fd);
        }
    }

    public void killConnection(Client client) {
        if (client == null) return;

        Thread currentThread = Thread.currentThread();
        Thread pollThread = client.pollThread;
        if (!client.closeState.tryBeginClose()) {
            // An external closer waits for the one close owner. The poll thread
            // must return immediately because that owner may be joining it.
            if (currentThread != pollThread) client.closeState.awaitClosed();
            return;
        }

        try {
            client.connected = false;
            if (pollThread != null && currentThread != pollThread) {
                client.requestShutdown();
                joinUninterruptibly(pollThread);
            }

            removeClient(client);
            if (client.connectionHandlerInitialized) {
                connectionHandler.handleConnectionShutdown(client);
            }
        }
        finally {
            if (client.shutdownFd >= 0) closeFd(client.shutdownFd);
            else removeFdFromEpoll(epollFd, client.clientSocket.fd);
            closeFd(client.clientSocket.fd);
            client.pollThread = null;
            client.closeState.finishClose();
        }
    }

    private void shutdown() {
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        while (true) {
            Client client = getLastClient();
            if (client == null) break;
            try {
                killConnection(client);
            }
            catch (RuntimeException e) {
                if (runtimeFailure == null) runtimeFailure = e;
            }
            catch (Error e) {
                if (errorFailure == null) errorFailure = e;
            }
        }

        removeFdFromEpoll(epollFd, serverFd);
        removeFdFromEpoll(epollFd, shutdownFd);
        closeFd(serverFd);
        closeFd(shutdownFd);
        closeFd(epollFd);

        if (errorFailure != null) throw errorFailure;
        if (runtimeFailure != null) throw runtimeFailure;
    }

    private void addClient(Client client) {
        synchronized (connectedClients) {
            connectedClients.put(client.clientSocket.fd, client);
        }
    }

    private void removeClient(Client client) {
        synchronized (connectedClients) {
            int fd = client.clientSocket.fd;
            if (connectedClients.get(fd) == client) connectedClients.remove(fd);
        }
    }

    private Client getLastClient() {
        synchronized (connectedClients) {
            int size = connectedClients.size();
            return size > 0 ? connectedClients.valueAt(size - 1) : null;
        }
    }

    private static void joinUninterruptibly(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            }
            catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public int getInitialInputBufferCapacity() {
        return initialInputBufferCapacity;
    }

    public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
        this.initialInputBufferCapacity = initialInputBufferCapacity;
    }

    public int getInitialOutputBufferCapacity() {
        return initialOutputBufferCapacity;
    }

    public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
        this.initialOutputBufferCapacity = initialOutputBufferCapacity;
    }

    public boolean isMultithreadedClients() {
        return multithreadedClients;
    }

    public void setMultithreadedClients(boolean multithreadedClients) {
        this.multithreadedClients = multithreadedClients;
    }

    public boolean isCanReceiveAncillaryMessages() {
        return canReceiveAncillaryMessages;
    }

    public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
        this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
    }

    private void requestShutdown() {
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(8);
            data.asLongBuffer().put(1);
            (new ClientSocket(shutdownFd)).write(data);
        }
        catch (IOException e) {}
    }

    public static native void closeFd(int fd);

    private native int createEpollFd();

    private native int createEventFd();

    native void wakeClientPoll(int shutdownFd, int clientFd);

    private native boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll);

    private native boolean addFdToEpoll(int epollFd, int fd);

    private native void removeFdFromEpoll(int epollFd, int fd);

    private native boolean waitForSocketRead(int clientFd, int shutdownFd);

    private native int createAFUnixSocket(String path);
}
