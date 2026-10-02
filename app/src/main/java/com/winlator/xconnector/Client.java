package com.winlator.xconnector;

import java.nio.ByteOrder;

public class Client {
    public final ClientSocket clientSocket;
    private final XConnectorEpoll connector;
    final ClientCloseState closeState = new ClientCloseState();
    private XInputStream inputStream;
    private XOutputStream outputStream;
    private Object tag;
    protected volatile Thread pollThread;
    protected int shutdownFd = -1;
    protected volatile boolean connected;
    protected volatile boolean connectionHandlerInitialized;

    public Client(XConnectorEpoll connector, ClientSocket clientSocket) {
        this.connector = connector;
        this.clientSocket = clientSocket;
    }

    public void createIOStreams() {
        if (inputStream != null || outputStream != null) return;
        inputStream = new XInputStream(clientSocket, connector.getInitialInputBufferCapacity());
        outputStream = new XOutputStream(clientSocket, connector.getInitialOutputBufferCapacity());
        inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
    }

    public XInputStream getInputStream() {
        return inputStream;
    }

    public XOutputStream getOutputStream() {
        return outputStream;
    }

    public Object getTag() {
        return tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

    protected void requestShutdown() {
        connector.wakeClientPoll(shutdownFd, clientSocket.fd);
    }
}
