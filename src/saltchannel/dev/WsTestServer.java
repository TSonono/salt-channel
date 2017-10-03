package saltchannel.dev;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import saltchannel.ByteChannel;
import saltchannel.ComException;
import saltchannel.util.CryptoTestData;
import saltchannel.util.KeyPair;
import saltchannel.v2.SaltServerSession;

/**
 * WebSocket echo server over Salt Channel.
 * 
 * @author Frans Lundberg
 */
public class WsTestServer {
    public static final int DEFAULT_PORT = 2034;
    private final int port;
    private HashMap<WebSocket, WebSocketInfo> sockets = new HashMap<>();
    private ServerSessionFactory sessionFactory;
    private KeyPair sigKeyPair;
    
    public WsTestServer(int port, ServerSessionFactory sessionFactory) {
        this.port = port;
        this.sessionFactory = sessionFactory;
        this.sigKeyPair = CryptoTestData.bSig;   // server is "Bob"
    }
    
    public int getPort() {
        return port;
    }
    
    public void start() {
        InetSocketAddress address = new InetSocketAddress(port);
        WebSocketServer server = new WebSocketServer(address) {
            @Override
            public void onClose(WebSocket socket, int code, String reason, boolean remote) {
                synchronized (this) {
                    sockets.remove(socket);
                }
            }

            @Override
            public void onError(WebSocket socket, Exception ex) {
                System.out.println("SERVER, onError, " + ex);
            }
            
            @Override
            public void onMessage(WebSocket socket, java.nio.ByteBuffer message) {
                byte[] bytes = message.array();
                synchronized (this) {
                    WebSocketInfo info = sockets.get(socket);
                    if (info != null) {
                        info.messageQ.add(bytes);
                    }
                }
            }

            @Override
            public void onMessage(WebSocket socket, String message) {
                // Should never happen, ignore.
            }

            @Override
            public void onOpen(final WebSocket socket, ClientHandshake handshake) {                
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        handleSocket(socket);
                    }
                });
                
                thread.start();
            }
        };
        
        server.start();
    }
    
    private void handleSocket(final WebSocket socket) {
        final WebSocketInfo socketInfo = new WebSocketInfo();
        
        try {
            synchronized (this) {
                sockets.put(socket, socketInfo);
            }
            
            ByteChannel clearChannel = new ByteChannel() {
                public byte[] read() throws ComException {
                    try {
                        return socketInfo.messageQ.take();
                    } catch (InterruptedException e) {
                        throw new ComException(e.getMessage());
                    }
                }

                public void write(byte[]... messages) throws ComException {
                    write(false, messages);
                }
                
                public void write(boolean isLast, byte[]... messages) throws ComException {
                    for (int i = 0; i < messages.length; i++) {
                        socket.send(messages[i]);
                    }
                }
            };
            
            SaltServerSession session = new SaltServerSession(sigKeyPair, clearChannel);
            session.setEncKeyPair(CryptoTestData.aEnc);
            session.setBufferM2(true);
            session.handshake();
            
            ByteChannelServerSession s = this.sessionFactory.createSession();
            s.runSession(session.getChannel());
            
        } finally {
            synchronized (this) {
                sockets.remove(socket);
            }
        }        
    }

    private static class WebSocketInfo {
        BlockingQueue<byte[]> messageQ;
        
        WebSocketInfo() {
            this.messageQ = new LinkedBlockingQueue<byte[]>();
        }
    }
    

    
    public static void main(String[] args) throws IOException, InterruptedException {
        ServerSessionFactory sessionFactory = new ServerSessionFactory() {
            public ByteChannelServerSession createSession() {
                return new EchoServerSession();
            }
        };
        
        WsTestServer s = new WsTestServer(WsTestServer.DEFAULT_PORT, sessionFactory);
        
        s.start();
        
        while (true) {
            Thread.sleep(100*1000);
        }
    }
}
