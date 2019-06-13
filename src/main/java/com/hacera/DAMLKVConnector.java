// (c) 2019 The Unbounded Network LTD
package com.hacera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class DAMLKVConnector {
    
    private FabricContext ctx;
    
    private class DAMLKVCache {
        long lastUsed;
        byte[] data;
        // written: this is set to False for entries that are not yet on the blockchain.
        //          this is so that with very high load (more than 100 entries per second), no data is dropped
        boolean written;
    }
    
    private static DAMLKVConnector instance;
    public static synchronized DAMLKVConnector get() {
        if (instance == null) {
            instance = new DAMLKVConnector();
        }
        
        return instance;
    }
    
    private class DAMLPushThread extends Thread {
        
        private class DAMLPushJob {
            public String fcn;
            public byte[][] args;
            public DAMLKVCache cacheEntry;
        }
        
        private final List<DAMLPushJob> jobs = new LinkedList<DAMLPushJob>();
        
        private DAMLPushJob checkJob() {
            synchronized (jobs) {
                if (!jobs.isEmpty()) {
                    return jobs.remove(0);
                }
            }
            
            return null;
        }
        
        public void push(DAMLKVCache cacheEntry, String fcn, byte[]... args) {
            DAMLPushJob job = new DAMLPushJob();
            job.fcn = fcn;
            job.args = args;
            job.cacheEntry = cacheEntry;
            synchronized (jobs) {
                jobs.add(job);
            }
        }
        
        public void push(DAMLKVCache cacheEntry, String fcn, String... args) {
            byte[][] byteArgs = new byte[args.length][];
            for (int i = 0; i < args.length; i++) {
                byteArgs[i] = args[i].getBytes(StandardCharsets.UTF_8);
            }
            push(cacheEntry, fcn, byteArgs);
        }
        
        @Override
        public void run() {
            while (true) {
                DAMLPushJob job;
                while ((job = checkJob()) != null) {
                    
                    // First off, wait until all peers are in sync
                    // We use system chaincode query to check for this...
                    long startEnsuring = System.currentTimeMillis();
                    // 30 seconds timeout to ensure channel height is equal for all peers
                    // the while loop is needed because we HAVE to push all messages before stopping the loop
                    boolean interrupted = false;
                    boolean fabricDied = false;
                    while (System.currentTimeMillis() < startEnsuring+30000) {
                        try {
                            ctx.ensureEndorsersInSync();
                            break;
                        } catch (Throwable t) {
                            if (InterruptedException.class.isAssignableFrom(t.getClass())) {
                                System.out.format("Fabric push thread is closing (InterruptedException) - trying to push last messages to persistence%n");
                                interrupted = true;
                            }
                            
                        }
                    }
                    
                    try {
                        ctx.invokeChaincode(job.fcn, job.args);
                    } catch (Throwable t) {
                        String msg = t.getMessage();
                        if (msg != null && msg.contains("code=UNAVAILABLE")) { // this normally means Fabric has gone away, we need to save command back
                            fabricDied = true;
                        } else {
                            System.err.format("Encountered exception while trying to push to Fabric [%s, %s]:%n", job.fcn, Arrays.toString(job.args));
                            t.printStackTrace(System.err);
                            System.err.format("This is normally fatal. Consider checking your Fabric connection...%n");
                        }
                    }
                    
                    if (job.cacheEntry != null) {
                        job.cacheEntry.written = true;
                    }
                    
                    if (fabricDied && !fabricWasOffline) {
                        fabricWasOffline = true;
                        synchronized(jobs) {
                            jobs.add(0, job);
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }

                        if (interrupted) {
                            System.out.format("Fabric push thread is closing (InterruptedException) - WARNING: not all messages have been written (Fabric is offline)");
                            break;
                        }
                    } else if (!fabricDied && fabricWasOffline) {
                        fabricWasOffline = false;
                        int h = getCommitHeight(true);
                        int jobsCount;
                        synchronized (jobs) {
                            jobsCount = jobs.size();
                        }
                        // this means that someone restarted Fabric while erasing all content
                        if (cacheCommitHeight - h > jobsCount) {
                            System.err.format("FATAL: Fabric has reappeared, but it's wrong Fabric (commit height differs too much)%n");
                            System.exit(255);
                        }
                    }
                    
                }
                
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    System.out.format("Fabric push thread is closing (InterruptedException)%n");
                    break;
                }
            }
        }
    }
    
    private static void logTime(String f, long time) {
        //System.out.format("%s : took %dms%n", f, System.currentTimeMillis()-time);
        //System.out.flush();
    }
    
    private String keyToString(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }
    
    private static final int MAX_CACHE = 100;
    private final Map<String, DAMLKVCache> kvCache;
    private final Map<Integer, DAMLKVCache> commitCache;
    DAMLKVCache packageListCache = null;
    private final Map<String, DAMLKVCache> packageCache;
    private final DAMLPushThread pushThread;
    private int cacheCommitHeight = -1;
    private boolean fabricWasOffline = false;
    
    private DAMLKVConnector() {
        ctx = new FabricContext();
        ctx.ensureChaincode();
        ExplorerService.Run(ctx);
        kvCache = new HashMap<String, DAMLKVCache>();
        commitCache = new HashMap<Integer, DAMLKVCache>();
        packageCache = new HashMap<String, DAMLKVCache>();
        pushThread = new DAMLPushThread();
        pushThread.start();
        cacheCommitHeight = getCommitHeight(true);
    }
    
    private void putValue(byte[] key, byte[] value, boolean cacheOnly) {
        // check cache size, if too large, remove last value
        // 100 keys
        long init = System.currentTimeMillis();
        String cacheKey = keyToString(key);
        DAMLKVCache newValue = new DAMLKVCache();
        newValue.lastUsed = System.currentTimeMillis();
        newValue.data = value;
        
        if (cacheOnly) {
            newValue.written = true;
        }
        
        synchronized (kvCache) {
            if (!kvCache.containsKey(cacheKey) && kvCache.size() >= MAX_CACHE) {
                String toRemoveKey = null;
                DAMLKVCache toRemove = null;
                for (Entry<String, DAMLKVCache> e : kvCache.entrySet()) {
                    DAMLKVCache comp = e.getValue();
                    if (!e.getValue().written) {
                        // do not remove values that are not written to Fabric yet, from cache
                        continue;
                    }
                    if (toRemove == null || comp.lastUsed < toRemove.lastUsed) {
                        toRemove = comp;
                        toRemoveKey = e.getKey();
                    }
                }
                if (toRemoveKey != null) {
                    kvCache.remove(toRemoveKey);
                }
            }
            
            kvCache.put(cacheKey, newValue);
        }
        
        if (!cacheOnly) {
            pushThread.push(newValue, "RawWrite", key, gzipBytes(value));
        }
        
        logTime("putValue", init);
    }
    
    public void putValue(byte[] key, byte[] value) {
        putValue(key, value, false);
    }
    
    public byte[] getValue(byte[] key) {
        long init = System.currentTimeMillis();
        String cacheKey = keyToString(key);
        synchronized (kvCache) {
            if (kvCache.containsKey(cacheKey)) {
                DAMLKVCache cache = kvCache.get(cacheKey);
                cache.lastUsed = System.currentTimeMillis();
                logTime("getValue cache", init);
                if (cache.data.length == 0)
                    return null;
                return cache.data;
            }
        }
        
        byte[] data = ctx.queryChaincode("RawRead", new byte[][]{ key });
        if (data != null && data.length > 0) {
            data = gunzipBytes(data);
        }
        putValue(key, data, true); // update cache with recently read value
        logTime("getValue", init);
        if (data.length == 0)
            return null;
        return data;
    }
    
    private DAMLKVCache putCommit(int index, byte[] commit, boolean written) {
        synchronized (commitCache) {
            DAMLKVCache newValue = new DAMLKVCache();
            newValue.lastUsed = System.currentTimeMillis();
            newValue.data = commit;
            newValue.written = written;
            
            if (!commitCache.containsKey(index) && commitCache.size() >= MAX_CACHE) {
                Integer toRemoveKey = null;
                DAMLKVCache toRemove = null;
                for (Entry<Integer, DAMLKVCache> e : commitCache.entrySet()) {
                    DAMLKVCache comp = e.getValue();
                    if (!e.getValue().written) {
                        // do not remove values that are not written to Fabric yet, from cache
                        continue;
                    }
                    if (toRemove == null || comp.lastUsed < toRemove.lastUsed) {
                        toRemove = comp;
                        toRemoveKey = e.getKey();
                    }
                }
                if (toRemoveKey != null) {
                    commitCache.remove(toRemoveKey);
                }
            } else if (commitCache.containsKey(index)) {
                return commitCache.get(index);
            }
            
            commitCache.put(index, newValue);
            return newValue;
        }
    }
    
    public int putCommit(byte[] commit) {
        long init = System.currentTimeMillis();
        int newIndex = ++cacheCommitHeight;
        logTime("putCommit", init);
        DAMLKVCache cacheObject = putCommit(newIndex-1, commit, false);
        pushThread.push(cacheObject, "WriteCommitLog", new byte[][]{ gzipBytes(commit) });
        return newIndex;
    }
    
    private int getCommitHeight(boolean onlyReal) {
        if (!onlyReal && cacheCommitHeight >= 0) {
            return cacheCommitHeight;
        }
        
        long init = System.currentTimeMillis();
        byte[] indexBytes = ctx.queryChaincode("ReadCommitHeight");
        logTime("getCommitHeight", init);
        if (indexBytes == null || indexBytes.length == 0)
            return 0;
        int index = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return index;
    }
    
    public int getCommitHeight() {
        return getCommitHeight(false);
    }
    
    public byte[] getCommit(int index) {
        long init = System.currentTimeMillis();
        synchronized (commitCache) {
            if (commitCache.containsKey(index)) {
                DAMLKVCache cache = commitCache.get(index);
                cache.lastUsed = System.currentTimeMillis();
                logTime("getCommit cache", init);
                if (cache.data.length == 0)
                    return null;
                return cache.data;
            }
        }
        
        byte[] data = ctx.queryChaincode("ReadCommit", Integer.toString(index));
        if (data != null && data.length > 0) {
            data = gunzipBytes(data);
        }
        putCommit(index, data, true); // update cache
        logTime("getCommit", init);
        if (data.length == 0)
            return null;
        return data;
    }
    
    private byte[] gzipBytes(byte[] data) {
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream out = new GZIPOutputStream(baos);
            out.write(data);
            out.close();
            return baos.toByteArray();
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException) t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
    }
    
    private byte[] gunzipBytes(byte[] data) {
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            GZIPInputStream in = new GZIPInputStream(bais);
            byte[] b = new byte[1024];
            int len;            
            while ((len = in.read(b)) != -1) {
                baos.write(b, 0, len);
            }
            in.close();
            bais.close();
            return baos.toByteArray();
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException) t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
    }
    
    private void putPackage(String cacheKey, byte[] value, boolean cacheOnly) {
        // check cache size, if too large, remove last value
        // 100 keys
        long init = System.currentTimeMillis();
        DAMLKVCache newValue = new DAMLKVCache();
        newValue.lastUsed = System.currentTimeMillis();
        newValue.data = value;
        
        if (cacheOnly) {
            newValue.written = true;
        }
        
        synchronized (packageCache) {
            if (!packageCache.containsKey(cacheKey) && packageCache.size() >= MAX_CACHE) {
                String toRemoveKey = null;
                DAMLKVCache toRemove = null;
                for (Entry<String, DAMLKVCache> e : packageCache.entrySet()) {
                    DAMLKVCache comp = e.getValue();
                    if (!e.getValue().written) {
                        // do not remove values that are not written to Fabric yet, from cache
                        continue;
                    }
                    if (toRemove == null || comp.lastUsed < toRemove.lastUsed) {
                        toRemove = comp;
                        toRemoveKey = e.getKey();
                    }
                }
                if (toRemoveKey != null) {
                    packageCache.remove(toRemoveKey);
                }
            }
            
            packageCache.put(cacheKey, newValue);
        }
        
        if (!cacheOnly) {
            pushThread.push(newValue, "PackageWrite", cacheKey.getBytes(StandardCharsets.UTF_8), gzipBytes(value));
            synchronized (this) {
                packageListCache = null;
            }
        }
        
        logTime("putPackage", init);
    }

    public void putPackage(String cacheKey, byte[] value) {
        putPackage(cacheKey, value, false);
    }
    
    public byte[] getPackage(String cacheKey) {
        long init = System.currentTimeMillis();
        synchronized (packageCache) {
            if (packageCache.containsKey(cacheKey)) {
                DAMLKVCache cache = packageCache.get(cacheKey);
                cache.lastUsed = System.currentTimeMillis();
                logTime("getPackage cache", init);
                if (cache.data.length == 0)
                    return null;
                return cache.data;
            }
        }
        
        byte[] data = ctx.queryChaincode("PackageRead", new byte[][] { cacheKey.getBytes(StandardCharsets.UTF_8) });
        if (data != null && data.length > 0) {
            data = gunzipBytes(data);
        }
        putPackage(cacheKey, data, true); // update cache with recently read value
        logTime("getPackage", init);
        if (data.length == 0)
            return null;
        return data;        
    }
    
    private byte[] getPackageListBytes() {
        long init = System.currentTimeMillis();
        synchronized (this) {
            if (packageListCache != null) {
                packageListCache.lastUsed = System.currentTimeMillis();
                return packageListCache.data;
            }
        }
        
        byte[] data = ctx.queryChaincode("PackageListRead");
        logTime("getPackageListBytes", init);
        synchronized(this) {
            packageListCache = new DAMLKVCache();
            packageListCache.data = data;
            packageListCache.lastUsed = System.currentTimeMillis();
            return data;
        }
    }
    
    public String[] getPackageList() {
        long init = System.currentTimeMillis();
        byte[] data = getPackageListBytes();
        ByteBuffer dataView = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int packagesCount = dataView.getInt(0);
        String[] packages = new String[packagesCount];
        int offset = 4;
        for (int i = 0; i < packagesCount; i++) {
            int packageLen = dataView.getInt(offset);
            byte[] packageBytes = new byte[packageLen];
            for (int j = 0; j < packageLen; j++) {
                packageBytes[j] = data[offset+4+j];
            }
            packages[i] = new String(packageBytes, StandardCharsets.UTF_8);
            offset += 4 + packageLen;
        }
        logTime("getPackageList", init);
        return packages;
    }
    
    public void shutdown() {
        // basically wait until all stuff is written to Fabric
        // if push thread died, throws a fatal error
        if (!pushThread.isAlive()) {
            throw new RuntimeException("Fabric push thread died, but service is about to close. Some data is lost!");
        }
        
        pushThread.interrupt();
        try {
            pushThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
    }
    
}
