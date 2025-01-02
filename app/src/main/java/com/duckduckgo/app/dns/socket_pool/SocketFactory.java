package com.duckduckgo.app.dns.socket_pool;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/**
 * Class responsible for initializing, destroying, or validating a Socket connection
 * in the pool.
 */
public class SocketFactory implements PooledObjectFactory <SocketClient> {
    
    private final String host;
    private final String dnsHostName;
    private final int port;

    public SocketFactory(String host, int port, String dnsHostName) {
        this.host = host;
        this.port = port;
        this.dnsHostName = dnsHostName;
    }
    
    private SocketClient create() throws Exception {
        return new SocketClient(this.host, this.port, this.dnsHostName);
    }

    private PooledObject<SocketClient> wrap(SocketClient t) {
        return new DefaultPooledObject<>(t);
    }

    @Override
    public PooledObject<SocketClient> makeObject() throws Exception {
        return this.wrap(this.create());
    }

    @Override
    public void destroyObject(PooledObject<SocketClient> p) throws Exception {
        p.getObject().close();
    }

    @Override
    public boolean validateObject(PooledObject<SocketClient> p) {
        return p.getObject().isValid();
    }

    @Override
    public void activateObject(PooledObject<SocketClient> p) throws Exception {
        p.getObject().activate();
    }

    @Override
    public void passivateObject(PooledObject<SocketClient> p) throws Exception {
        p.getObject().deactivate();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
    
    

}
