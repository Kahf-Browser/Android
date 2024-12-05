package com.duckduckgo.app.dns.socket_pool;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocketHelper {

    private static final Logger LOGGER = Logger.getLogger(SocketHelper.class.getName());

    private final static Map<String, SocketHelper> socketPools = new HashMap<>();


    private final SocketPool socketPool;

    /**
     * Private constructor to hide it
     */
    private SocketHelper(SocketPool socketPool) {
        this.socketPool = socketPool;
    }

    /**
     * Method responsible for obtaining a unique instance in which
     * the pool of socket clients is located.
     *
     * @param host Host of the socket to connect to
     * @param port Port of the socket to connect to
     * @return Unique instance of the helper
     */
    public static SocketHelper getInstance(String host, int port, String dnsHostName) {
        String key = host + ":" + port;
        if (!socketPools.containsKey(key)) {
            synchronized (SocketHelper.class) {
                SocketFactory factory = new SocketFactory(host, port, dnsHostName);
                GenericObjectPoolConfig config = getDefaultConfig();
                SocketPool newPool = new SocketPool(factory, config);
                try {
                    newPool.preparePool();
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, "Error trying to initialize the pool", ex);
                }
                SocketHelper newInstance = new SocketHelper(newPool);
                socketPools.put(key, newInstance);
            }
        }
        return socketPools.get(key);
    }

    public void shutDown() {
        String key = ((SocketFactory) socketPool.getFactory()).getHost() + ":" +
                ((SocketFactory) socketPool.getFactory()).getPort();
        socketPools.remove(key);
    }

    /**
     * Generates an object with the default configuration desired for the
     * generated socket pools.
     *
     * @return Configuration
     */
    private static GenericObjectPoolConfig getDefaultConfig() {
        GenericObjectPoolConfig defaultConfig = new GenericObjectPoolConfig();
        defaultConfig.setJmxEnabled(false);
        return defaultConfig;
    }

    public SocketClient getSocket() {
        try {
            return this.socketPool.borrowObject();
        } catch (Exception ex) {
            Logger.getLogger(SocketHelper.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    /**
     * Returns the socket client instance to the pool.
     *
     * @param socketClient Instance to be returned.
     */
    public void returnSocket(SocketClient socketClient) {
        this.socketPool.returnObject(socketClient);
    }

    public void invalidateSocket(SocketClient socketClient) {
        try {
            this.socketPool.invalidateObject(socketClient);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Error invalidating the socket", ex);
        }
    }

    /**
     * Sets configuration values in the pool.
     *
     * @param config Configuration properties
     */
    public void setConfiguration(GenericObjectPoolConfig config) {
        this.socketPool.setConfig(config);
        try {
            this.socketPool.preparePool();
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Error tratanto de resetear el pool", ex);
        }
    }

    /**
     * Logs the current status of the pool.
     */
    public String logStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n-------------------------------------").append("\n")
                .append("[Max Size:").append(this.socketPool.getMaxTotal()).append("]")
                .append("[Min Size:").append(this.socketPool.getMinIdle()).append("]")
                .append("[Used Instances:").append(this.socketPool.getNumActive()).append("]")
                .append("[Unused Instances: ").append(this.socketPool.getNumIdle()).append("]")
                .append("[Total Instances: ").append(this.socketPool.getNumIdle() + this.socketPool.getNumActive()).append("]")
                .append("[Requests in Queue: ").append(this.socketPool.getNumWaiters()).append("]")
                .append("\n-------------------------------------").append("\n");
        LOGGER.log(Level.INFO, sb.toString());

        return sb.toString();
    }


    /**
     * The features are current configurations or states of the pool.
     *
     * @param feature Pool feature to query
     * @return Found value
     */
    public int getFeature(StatusPoolFeature feature) {
        switch (feature) {
            case NUM_ACTIVE:
                return this.socketPool.getNumActive();
            case NUM_IDLE:
                return this.socketPool.getNumIdle();
            case NUM_WAITERS:
                return this.socketPool.getNumWaiters();
            case MAX_TOTAL:
                return this.socketPool.getMaxTotal();
        }
        return -1;
    }

    /**
     * Enum with the pool features that can be queried
     */
    public enum StatusPoolFeature {
        NUM_ACTIVE,
        NUM_IDLE,
        NUM_WAITERS,
        MAX_TOTAL;
    }

}
