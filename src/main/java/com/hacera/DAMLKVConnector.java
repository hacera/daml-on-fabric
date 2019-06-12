// (c) 2019 The Unbounded Network LTD
package com.hacera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class DAMLKVConnector {
    
    private FabricContext ctx;
    
    private class DAMLKVCache {
        long lastUsed;
        byte[] data;
    }
    
    private static DAMLKVConnector instance;
    public static synchronized DAMLKVConnector get() {
        if (instance == null) {
            instance = new DAMLKVConnector();
        }
        
        return instance;
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
    
    private DAMLKVConnector() {
        ctx = new FabricContext();
        ctx.ensureChaincode();
        ExplorerService.Run(ctx);
        kvCache = new HashMap<String, DAMLKVCache>();
        commitCache = new HashMap<Integer, DAMLKVCache>();
        packageCache = new HashMap<String, DAMLKVCache>();
        String[] packages = getPackageList();
        System.out.format("installed packages len = %d%n", packages.length);
    }
    
    private void putValue(byte[] key, byte[] value, boolean cacheOnly) {
        // check cache size, if too large, remove last value
        // 100 keys
        long init = System.currentTimeMillis();
        if (!cacheOnly) {
            ctx.invokeChaincode("RawWrite", key, gzipBytes(value));
        }

        String cacheKey = keyToString(key);
        DAMLKVCache newValue = new DAMLKVCache();
        newValue.lastUsed = System.currentTimeMillis();
        newValue.data = value;
        
        synchronized (kvCache) {
            if (!kvCache.containsKey(cacheKey) && kvCache.size() >= MAX_CACHE) {
                String toRemoveKey = null;
                DAMLKVCache toRemove = null;
                for (Entry<String, DAMLKVCache> e : kvCache.entrySet()) {
                    DAMLKVCache comp = e.getValue();
                    if (toRemove == null || comp.lastUsed < toRemove.lastUsed) {
                        toRemove = comp;
                        toRemoveKey = e.getKey();
                    }
                }
                kvCache.remove(toRemoveKey);
            }
            
            kvCache.put(cacheKey, newValue);
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
    
    private void putCommit(int index, byte[] commit) {
        long init = System.currentTimeMillis();
        synchronized (commitCache) {
            DAMLKVCache newValue = new DAMLKVCache();
            newValue.lastUsed = System.currentTimeMillis();
            newValue.data = commit;
            
            if (!commitCache.containsKey(index) && commitCache.size() >= MAX_CACHE) {
                Integer toRemoveKey = null;
                DAMLKVCache toRemove = null;
                for (Entry<Integer, DAMLKVCache> e : commitCache.entrySet()) {
                    DAMLKVCache comp = e.getValue();
                    if (toRemove == null || comp.lastUsed < toRemove.lastUsed) {
                        toRemove = comp;
                        toRemoveKey = e.getKey();
                    }
                }
                commitCache.remove(toRemoveKey);
            }
            
            commitCache.put(index, newValue);
        }
        logTime("putCommit cache", init);
    }
    
    public int putCommit(byte[] commit) {
        long init = System.currentTimeMillis();
        byte[] newIndexBytes = ctx.invokeChaincode("WriteCommitLog", new byte[][]{ gzipBytes(commit) });
        int newIndex = ByteBuffer.wrap(newIndexBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        logTime("putCommit", init);
        putCommit(newIndex-1, commit);
        return newIndex;
    }
    
    public int getCommitHeight() {
        long init = System.currentTimeMillis();
        byte[] indexBytes = ctx.invokeChaincode("ReadCommitHeight");
        logTime("getCommitHeight", init);
        if (indexBytes == null || indexBytes.length == 0)
            return 0;
        int index = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return index;
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
        putCommit(index, data); // update cache
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
        if (!cacheOnly) {
            ctx.invokeChaincode("PackageWrite", cacheKey.getBytes(StandardCharsets.UTF_8), gzipBytes(value));
            synchronized (this) {
                packageListCache = null;
            }
        }

        DAMLKVCache newValue = new DAMLKVCache();
        newValue.lastUsed = System.currentTimeMillis();
        newValue.data = value;
        
        synchronized (packageCache) {
            if (!packageCache.containsKey(cacheKey) && packageCache.size() >= MAX_CACHE) {
                String toRemoveKey = null;
                DAMLKVCache toRemove = null;
                for (Entry<String, DAMLKVCache> e : packageCache.entrySet()) {
                    DAMLKVCache comp = e.getValue();
                    if (toRemove == null || comp.lastUsed < toRemove.lastUsed) {
                        toRemove = comp;
                        toRemoveKey = e.getKey();
                    }
                }
                packageCache.remove(toRemoveKey);
            }
            
            packageCache.put(cacheKey, newValue);
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
    
}
