package com.duckduckgo.app.dns.socket_pool;

import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;


public class SocketPool extends GenericObjectPool<SocketClient> {

    public SocketPool(PooledObjectFactory<SocketClient> factory) {
        super(factory);
    }

    public SocketPool(PooledObjectFactory<SocketClient> factory, GenericObjectPoolConfig config) {
        super(factory, config);
    }
}
